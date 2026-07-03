/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.IpHasher;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.core.filter.VanillaGrieferSet;
import network.vonix.guardian.mc.v1_21_1.common.ChatRenderer;
import network.vonix.guardian.mc.v1_21_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_21_1.common.GuardianCommands;
import network.vonix.guardian.mc.v1_21_1.common.Inspector;
import network.vonix.guardian.mc.v1_21_1.common.SourceTagger;
import network.vonix.guardian.mc.v1_21_1.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static {@code @SubscribeEvent} handlers translating NeoForge events into
 * {@link EventSubmitter} calls per SHARED-LOADER-CONTRACTS § 3.
 *
 * <p>All handlers catch {@link Throwable} and log via the {@code VONIXGUARDIAN}
 * marker — never throw back into the server thread.
 */
public final class NeoForgeEvents {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeEvents.class);

    /** Per-entity-type rate limit for spawn logging (last-emit ts in millis). */
    // v1.3.0 W2: de-boxed spawn-limit throttle. Previous Map<String,Long> auto-boxed a
    // fresh Long on every put(...) — several MB/s of short-lived garbage on modded
    // shards with mob-farm-heavy chunks (piglin bartering, drowned trident farms, etc.).
    // AtomicLong lets us update in place with a plain volatile store on the hot path.
    private static final Map<String, AtomicLong> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    /** Daemon worker for off-tick JDBC (inspector lookups). */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VonixGuardian-NeoForgeEvents-Worker");
        t.setDaemon(true);
        return t;
    });

    /** Last right-clicked container position per player (for open/close snapshot diff). */
    private static final Map<UUID, BlockPos> LAST_CONTAINER_RC = new ConcurrentHashMap<>();
    /** Wall-clock time (ms) of the last right-clicked container per player. */
    private static final Map<UUID, Long> LAST_CONTAINER_RC_AT = new ConcurrentHashMap<>();
    /** Snapshot of container contents at open time, keyed by player UUID. */
    private static final Map<UUID, Map<Integer, ItemStack>> CONTAINER_SNAPSHOT = new ConcurrentHashMap<>();
    /** Position the snapshot was taken at, keyed by player UUID. */
    private static final Map<UUID, BlockPos> CONTAINER_SNAPSHOT_POS = new ConcurrentHashMap<>();
    /** Wall-clock time (ms) of the snapshot, keyed by player UUID. */
    private static final Map<UUID, Long> CONTAINER_SNAPSHOT_AT = new ConcurrentHashMap<>();
    /** Recent-RC window for matching right-click -> open. */
    private static final long CONTAINER_RC_WINDOW_MS = 500L;
    /** Timeout for abandoned container snapshots (disconnect/crash/no close event). */
    private static final long CONTAINER_SNAPSHOT_TTL_MS = 5 * 60 * 1000L;
    /** Hard cap on simultaneously retained snapshots. */
    private static final int MAX_CONTAINER_SNAPSHOTS = 512;
    /** Hard cap on per-container slots retained to keep modded mega-containers bounded. */
    private static final int MAX_CONTAINER_SLOTS = 216;
    /**
     * v1.3.0 W3: counter for cleanupContainerSnapshots amortization. Old path
     * scanned the full CONTAINER_SNAPSHOT_AT map on EVERY container open — O(n)
     * per open even when there is nothing to evict. Now we only run the full
     * TTL scan every {@link #CONTAINER_CLEANUP_EVERY} opens OR when the map
     * exceeds {@link #MAX_CONTAINER_SNAPSHOTS} (bounded-work fast-path
     * eviction still runs immediately in that case).
     */
    private static final java.util.concurrent.atomic.AtomicInteger CONTAINER_CLEANUP_TICK
            = new java.util.concurrent.atomic.AtomicInteger();
    /** Run cleanupContainerSnapshots' full TTL scan every N opens; power-of-two so we can mask. */
    private static final int CONTAINER_CLEANUP_EVERY = 32;

    /**
     * v1.3.0 W3: pooled scratch buffer for onExplosionDetonate capture. One
     * per server thread — reused across explosions to avoid re-allocating
     * 3× int[] + 1× String[] per detonation. Grows on demand up to a per-cell
     * cap; a large TNT chain briefly bumps it, subsequent explosions reuse.
     */
    private static final ThreadLocal<ExplosionScratch> EXPLOSION_SCRATCH =
            ThreadLocal.withInitial(ExplosionScratch::new);

    private static final class ExplosionScratch {
        int[] xs = new int[512];
        int[] ys = new int[512];
        int[] zs = new int[512];
        String[] ids = new String[512];
        void grow(int need) {
            if (xs.length >= need) return;
            int n = xs.length;
            while (n < need) n <<= 1;
            xs = new int[n]; ys = new int[n]; zs = new int[n]; ids = new String[n];
        }
    }

    private NeoForgeEvents() {
        // utility
    }

    // ====================================================================== access

    private static Guardian g() {
        return VonixGuardianNeoForge.guardian();
    }

    private static EventSubmitter sub() {
        Guardian g = g();
        return g == null ? null : g.submitter();
    }

    private static GuardianConfig cfg() {
        Guardian g = g();
        return g == null ? null : g.config();
    }

    /**
     * v1.3.2 Y1: config-gated NBT-persist flag. When {@code false} (default),
     * producers skip the NBT capture path entirely — no allocation, no BE
     * lookup, no ItemStack.save. Safe to call from event handlers because
     * the config accessor returns the last-loaded snapshot.
     */
    private static boolean persistNbt() {
        GuardianConfig c = cfg();
        return c != null && c.storage() != null && c.storage().persistNbt();
    }

    // ====================================================================== blocks

    /** {@code BlockEvent.BreakEvent}: log the break; if inspector, cancel + lookup. */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getPlayer();
            if (p == null) return;
            if (Inspector.isInspecting(p.getUUID())) {
                ev.setCanceled(true);
                Guardian gg = g();
                if (p instanceof ServerPlayer sp) {
                    // Re-sync the client; the cancel doesn't always propagate.
                    sp.connection.send(new ClientboundBlockUpdatePacket(
                            ev.getPos(), ev.getState()));
                    if (gg != null) {
                        BlockPos pos = ev.getPos();
                        sp.sendSystemMessage(ChatRenderer.primary(gg.theme(),
                                "[VonixGuardian] inspect @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
                    }
                }
                return;
            }
            BlockPos pos = ev.getPos();
            BlockState state = ev.getState();
            String blockId = blockId(state);
            String worldId = WorldKey.of((Level) ev.getLevel());
            // v1.3.2 Y1: NBT capture on the server thread BEFORE the break resolves.
            if (persistNbt()) {
                Level lvl = (Level) ev.getLevel();
                String stateProps = NbtCapture.blockStateProps(state);
                BlockEntity be = lvl.getBlockEntity(pos);
                byte[] beNbt = be == null ? null : NbtCapture.blockEntity(be, lvl.registryAccess());
                s.submitBlockBreak(p.getUUID(), p.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(),
                        blockId, null, stateProps, beNbt);
            } else {
                s.submitBlockBreak(p.getUUID(), p.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(), blockId, null);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onBlockBreak failed", t);
        }
    }

    /** {@code BlockEvent.EntityPlaceEvent}: log placement. */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Entity actor = ev.getEntity();
            BlockPos pos = ev.getPos();
            String blockId = blockId(ev.getPlacedBlock());
            String worldId = WorldKey.of((Level) ev.getLevel());
            if (actor instanceof Player p) {
                s.submitBlockPlace(p.getUUID(), p.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(), blockId, null);
            } else if (actor != null) {
                s.submitBlockPlace(null, EntitySentinel.of(actor),
                        worldId, pos.getX(), pos.getY(), pos.getZ(), blockId,
                        SourceTagger.tag(actor));
            } else {
                s.submitBlockPlace(null, Sentinel.UNKNOWN,
                        worldId, pos.getX(), pos.getY(), pos.getZ(), blockId, null);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onBlockPlace failed", t);
        }
    }

    /** Universal modded griefing path: any LivingEntity destroying a block. */
    @SubscribeEvent
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent ev) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            LivingEntity e = ev.getEntity();
            if (e == null) return;
            String entityKey = stripMobPrefix(EntitySentinel.of(e.getType()));
            if (!VanillaGrieferSet.shouldRecord(entityKey,
                    c.actions().entityChangeAllowlist(),
                    c.actions().entityChangeLogAllEntities())) {
                return;
            }
            Attribution attr = NeoForgeBootstrap.resolver != null
                    ? NeoForgeBootstrap.resolver.resolve(e, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.of(e));
            BlockPos pos = ev.getPos();
            String oldId = blockId(ev.getState());
            s.submitEntityChangeBlock(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(e.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    oldId, "minecraft:air", attr.entitySentinel());
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDestroyBlock failed", t);
        }
    }

    private static String stripMobPrefix(String sentinel) {
        if (sentinel == null || !sentinel.startsWith("#mob:")) return null;
        return sentinel.substring(5);
    }

    /**
     * Explosion detonate — v1.3.0 W3: server thread does the (unavoidable)
     * per-pos {@code getBlockState} + registry key lookup into pooled scratch
     * arrays, then hands the {@link StringBuilder} join + queue enqueue to
     * {@link network.vonix.guardian.core.event.ExplosionJoinWorker}. A 5,000-block
     * TNT chain no longer builds a 4 KiB {@link StringBuilder} on the tick;
     * the worker splits the join into chunked {@code EXPLOSION} rows sharing
     * the same center + source tag.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            var affected = ev.getAffectedBlocks();
            if (affected == null || affected.isEmpty()) return;
            ExplosionScratch scratch = EXPLOSION_SCRATCH.get();
            int n = affected.size();
            scratch.grow(n);
            // Server-thread capture: getBlockState + registry-key lookup are
            // NOT documented as thread-safe on ServerLevel. We match Prism /
            // Ledger / CoreProtect: capture ids synchronously, defer the join.
            int idx = 0;
            for (BlockPos p : affected) {
                scratch.xs[idx] = p.getX();
                scratch.ys[idx] = p.getY();
                scratch.zs[idx] = p.getZ();
                scratch.ids[idx] = blockId(ev.getLevel().getBlockState(p));
                idx++;
            }
            Entity source = ev.getExplosion().getDirectSourceEntity();
            BlockPos center = BlockPos.containing(ev.getExplosion().center());
            String worldId = WorldKey.of((Level) ev.getLevel());
            // v1.3.1 X7: PrimedTnt has no vanilla igniter accessor from
            // ExplosionEvent.Detonate, so consult TntPrimeMemory populated by
            // TntBlockMixin / PrimedTntEntityMixin at prime-time. Do the
            // lookup against the exploding entity's own block position when
            // the source is a PrimedTnt.
            Attribution attr = null;
            String sourceTagOverride = null;
            Guardian gEarly = g();
            if (gEarly != null && source instanceof net.minecraft.world.entity.item.PrimedTnt) {
                BlockPos originPos = source.blockPosition();
                Attribution primed = network.vonix.guardian.core.attribution.UniversalAttribution
                        .resolveTntPrime(gEarly.tntPrimeMemory(), worldId,
                                originPos.getX(), originPos.getY(), originPos.getZ(),
                                Sentinel.TNT);
                if (primed != null) {
                    attr = primed;
                    sourceTagOverride = "#tnt";
                }
            }
            if (attr == null) {
                attr = (source != null && NeoForgeBootstrap.resolver != null)
                        ? NeoForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())
                        : Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            String sourceTag = sourceTagOverride != null ? sourceTagOverride
                    : (source != null ? SourceTagger.tag(source) : Sentinel.EXPLOSION);
            String actorName = attr.actorName() != null ? attr.actorName() : Sentinel.EXPLOSION;
            Guardian g = g();
            if (g == null) return;
            g.explosionJoinWorker().submit(s, attr.actorUuid(), actorName, worldId,
                    center.getX(), center.getY(), center.getZ(), sourceTag,
                    scratch.xs, scratch.ys, scratch.zs, scratch.ids, idx);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onExplosionDetonate failed", t);
        }
    }

    // ====================================================================== combat

    /** Record player attackers into the damage history (universal attribution step 4). */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Pre ev) {
        try {
            if (NeoForgeBootstrap.damageHistory == null) return;
            Entity src = ev.getSource().getEntity();
            LivingEntity victim = ev.getEntity();
            if (victim == null) return;
            if (src instanceof Player p) {
                NeoForgeBootstrap.damageHistory.record(victim.getUUID(), p.getUUID(),
                        System.currentTimeMillis());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDamage failed", t);
        }
    }

    /** Log entity deaths via universal attribution. */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            LivingEntity victim = ev.getEntity();
            if (victim == null) return;

            Entity killer = ev.getSource().getEntity();
            Attribution attr;
            if (killer != null && NeoForgeBootstrap.resolver != null) {
                attr = NeoForgeBootstrap.resolver.resolve(killer, System.currentTimeMillis());
            } else {
                attr = Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            BlockPos pos = victim.blockPosition();
            String entityType = EntitySentinel.of(victim);
            // v1.3.2 Y1: capture the victim's persistent NBT BEFORE death
            // resolves so custom names, tame owner, potion effects survive
            // a rollback.
            byte[] entNbt = persistNbt() ? NbtCapture.entity(victim) : null;
            if (entNbt != null) {
                s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                        WorldKey.of(victim.level()),
                        pos.getX(), pos.getY(), pos.getZ(),
                        entityType, SourceTagger.tag(ev.getSource()), entNbt);
            } else {
                s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                        WorldKey.of(victim.level()),
                        pos.getX(), pos.getY(), pos.getZ(),
                        entityType, SourceTagger.tag(ev.getSource()));
            }
            if (NeoForgeBootstrap.damageHistory != null) {
                NeoForgeBootstrap.damageHistory.forget(victim.getUUID());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDeath failed", t);
        }
    }

    // ====================================================================== entities

    /** Log non-player entity spawns; rate-limited per entity-type. */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent ev) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            if (!c.actions().logEntities()) return;
            Entity e = ev.getEntity();
            if (!(e instanceof LivingEntity) || e instanceof Player) return;
            // P0-7 classification: filter chunk-load reanimations. EntityJoinLevelEvent
            // fires for every entity, INCLUDING entities loading back into memory when a
            // chunk re-loads — that produced the ENTITY_SPAWN flood (213M dropped events
            // in 16 min on SB4). The full MobSpawnType classifier (NATURAL/SPAWNER/EGG/
            // BREEDING/etc.) needs LivingSpawnEvent.CheckSpawn or a Mixin and is queued for
            // 1.0.5; this filter cuts the bulk of the noise in 1.0.4.
            if (ev.loadedFromDisk()) return;
            String type = EntitySentinel.of(e);
            long now = System.currentTimeMillis();
            // v1.3.0 W2: de-boxed spawn-limit throttle — plain AtomicLong.set on the
            // hot path, no autoboxing per spawn.
            AtomicLong last = SPAWN_LIMIT.get(type);
            if (last == null) {
                AtomicLong fresh = new AtomicLong(now);
                AtomicLong prev = SPAWN_LIMIT.putIfAbsent(type, fresh);
                if (prev != null) prev.set(now);
            } else {
                if (now - last.get() < SPAWN_LIMIT_MS) return;
                last.set(now);
            }
            BlockPos pos = e.blockPosition();
            s.submitEntitySpawn(null, type,
                    WorldKey.of(ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(), type, "spawn:join");
        } catch (Throwable t) {
            // Rate-limit: one warn per minute per error class, otherwise this
            // can fire thousands of times per second on heavy modpacks where
            // the bytecode-link or another mod's mixin breaks for one entity
            // type.
            long now = System.currentTimeMillis();
            String key = t.getClass().getName() + ":" + (t.getMessage() == null ? "" : t.getMessage());
            Long last = ENTITY_JOIN_WARN_LIMIT.get(key);
            if (last == null || now - last >= 60_000L) {
                ENTITY_JOIN_WARN_LIMIT.put(key, now);
                LOG.warn(Guardian.MARKER, "onEntityJoinLevel failed", t);
            }
        }
    }

    private static final ConcurrentHashMap<String, Long> ENTITY_JOIN_WARN_LIMIT =
            new ConcurrentHashMap<>();

    // ====================================================================== sessions

    /** Player login -> session join row. */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent ev) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            Player p = ev.getEntity();
            if (!(p instanceof ServerPlayer sp)) return;
            String addr;
            try {
                addr = sp.connection.getRemoteAddress() != null
                        ? sp.connection.getRemoteAddress().toString()
                        : "";
            } catch (Throwable ignored) {
                addr = "";
            }
            String ipField = c.privacy().hashIps()
                    ? IpHasher.hash(addr, c.privacy().salt())
                    : addr;
            s.submitSessionJoin(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()), ipField);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onPlayerLoggedIn failed", t);
        }
    }

    /** Player logout -> session leave row. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getEntity();
            if (p == null) return;
            s.submitSessionLeave(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()), "logout");
            Inspector.forget(p.getUUID());
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onPlayerLoggedOut failed", t);
        }
    }

    // ====================================================================== chat / commands

    /**
     * Capture chat at {@code EventPriority.HIGHEST} with {@code receiveCanceled=true} so we
     * log the message BEFORE any other mod (anti-spam, chat-filter, mute) gets a chance to
     * cancel it, AND we still receive cancelled events from earlier listeners. NeoForge's
     * event-bus order is HIGHEST→HIGH→NORMAL→LOW→LOWEST, so HIGHEST runs first; pair with
     * receiveCanceled to match CoreProtect's contract: "log the chat as the user typed it,
     * even if downstream cancels broadcast".
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onServerChat(ServerChatEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            ServerPlayer p = ev.getPlayer();
            if (p == null) return;
            String msg = ev.getRawText();
            s.submitChat(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()), msg);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onServerChat failed", t);
        }
    }

    /** Command dispatched -> COMMAND row. */
    @SubscribeEvent
    public static void onCommand(CommandEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            ParseResults<CommandSourceStack> res = ev.getParseResults();
            if (res == null) return;
            CommandSourceStack src = res.getContext().getSource();
            String raw = res.getReader().getString();
            if (src.getEntity() instanceof ServerPlayer p) {
                s.submitCommand(p.getUUID(), p.getName().getString(),
                        WorldKey.of(p.level()), "/" + raw);
            } else {
                s.submitCommand(null, "#console", "minecraft:overworld", "/" + raw);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onCommand failed", t);
        }
    }

    // ====================================================================== items

    /** Player drops an item -> ITEM_DROP. */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getPlayer();
            ItemEntity ie = ev.getEntity();
            if (p == null || ie == null) return;
            ItemStack stack = ie.getItem();
            BlockPos pos = ie.blockPosition();
            byte[] itemNbt = persistNbt() ? NbtCapture.itemStack(stack, p.level().registryAccess()) : null;
            if (itemNbt != null) {
                s.submitItemDrop(p.getUUID(), p.getName().getString(),
                        WorldKey.of(p.level()),
                        pos.getX(), pos.getY(), pos.getZ(),
                        itemId(stack), stack.getCount(), null, itemNbt);
            } else {
                s.submitItemDrop(p.getUUID(), p.getName().getString(),
                        WorldKey.of(p.level()),
                        pos.getX(), pos.getY(), pos.getZ(),
                        itemId(stack), stack.getCount(), null);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onItemToss failed", t);
        }
    }

    /**
     * Item pickup — uses {@link ItemEntityPickupEvent.Post} on NeoForge 21.1.
     */
    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getPlayer();
            ItemStack stack = ev.getItemEntity() == null ? null : ev.getItemEntity().getItem();
            if (p == null || stack == null || stack.isEmpty()) return;
            BlockPos pos = p.blockPosition();
            s.submitItemPickup(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onItemPickup failed", t);
        }
    }

    /** Crafted item -> ITEM_CRAFT. */
    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getEntity();
            ItemStack stack = ev.getCrafting();
            if (p == null || stack == null || stack.isEmpty()) return;
            BlockPos pos = p.blockPosition();
            s.submitItemCraft(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onItemCrafted failed", t);
        }
    }

    // ====================================================================== interaction

    /**
     * Right-click block -&gt; CLICK if {@code logInteractions}; also snapshot
     * container pos for delta tracking.
     *
     * <p><b>v1.3.0 W3:</b> reads {@link BlockState} once and reuses it across
     * the container-entity detection and the CLICK submit. Old path called
     * {@code getBlockState}/{@code getBlockEntity} twice.</p>
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock ev) {
        try {
            Player p = ev.getEntity();
            if (p == null) return;
            BlockPos pos = ev.getPos();
            // v1.3.0 W3: single BlockState read reused below.
            BlockState state = ev.getLevel().getBlockState(pos);
            // Container snapshot tracking (independent of logInteractions config).
            try {
                if (p instanceof ServerPlayer sp
                        && state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock) {
                    // EntityBlock gate — cheap Block-class check rather than a
                    // chunk-BE-map hit for every RC on plain terrain.
                    BlockEntity be = ev.getLevel().getBlockEntity(pos);
                    if (be instanceof Container) {
                        LAST_CONTAINER_RC.put(sp.getUUID(), pos.immutable());
                        LAST_CONTAINER_RC_AT.put(sp.getUUID(), System.currentTimeMillis());
                    }
                }
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "onRightClickBlock (container snapshot) failed", t);
            }
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            if (!c.actions().logInteractions()) return;
            s.submitClick(p.getUUID(), p.getName().getString(),
                    WorldKey.of(ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(state), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onRightClickBlock failed", t);
        }
    }

    /** Generic right-click entity interactions: audit-only ENTITY_INTERACT, gated by logInteractions. */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract ev) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            if (!c.actions().logInteractions()) return;
            Player p = ev.getEntity();
            Entity target = ev.getTarget();
            if (p == null || target == null) return;
            String type = EntitySentinel.of(target);
            BlockPos pos = target.blockPosition();
            s.submitEntityInteract(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    type, SourceTagger.tag(target));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onEntityInteract failed", t);
        }
    }


    /**
     * v1.3.0 W3: amortized cleanup. Old path scanned the full
     * {@code CONTAINER_SNAPSHOT_AT} map on every container open (O(n) per open,
     * regardless of whether anything was actually expired). Now we run the
     * TTL scan only every {@link #CONTAINER_CLEANUP_EVERY} opens, PLUS an
     * immediate fast-path eviction when the snapshot map exceeds
     * {@link #MAX_CONTAINER_SNAPSHOTS} (bounded work per open).
     */
    private static void cleanupContainerSnapshots() {
        // Fast-path: bounded eviction if we're over cap regardless of counter phase.
        while (CONTAINER_SNAPSHOT.size() > MAX_CONTAINER_SNAPSHOTS) {
            evictOldestContainerSnapshot();
        }
        // Amortized: only run the TTL scan every N opens (mask; N is power-of-two).
        int tick = CONTAINER_CLEANUP_TICK.incrementAndGet();
        if ((tick & (CONTAINER_CLEANUP_EVERY - 1)) != 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - CONTAINER_SNAPSHOT_TTL_MS;
        CONTAINER_SNAPSHOT_AT.entrySet().removeIf(e -> {
            Long ts = e.getValue();
            if (ts != null && ts >= cutoff) return false;
            UUID id = e.getKey();
            CONTAINER_SNAPSHOT.remove(id);
            CONTAINER_SNAPSHOT_POS.remove(id);
            LAST_CONTAINER_RC.remove(id);
            LAST_CONTAINER_RC_AT.remove(id);
            return true;
        });
    }

    private static void evictOldestContainerSnapshot() {
        UUID oldest = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<UUID, Long> e : CONTAINER_SNAPSHOT_AT.entrySet()) {
            long ts = e.getValue() != null ? e.getValue() : Long.MIN_VALUE;
            if (ts < oldestTs) {
                oldestTs = ts;
                oldest = e.getKey();
            }
        }
        if (oldest == null && !CONTAINER_SNAPSHOT.isEmpty()) {
            oldest = CONTAINER_SNAPSHOT.keySet().iterator().next();
        }
        if (oldest != null) {
            CONTAINER_SNAPSHOT.remove(oldest);
            CONTAINER_SNAPSHOT_POS.remove(oldest);
            CONTAINER_SNAPSHOT_AT.remove(oldest);
            LAST_CONTAINER_RC.remove(oldest);
            LAST_CONTAINER_RC_AT.remove(oldest);
        }
    }

    /** Container opened -> snapshot contents for diff at close. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open ev) {
        try {
            if (!(ev.getEntity() instanceof ServerPlayer sp)) return;
            cleanupContainerSnapshots();
            UUID id = sp.getUUID();
            Long ts = LAST_CONTAINER_RC_AT.get(id);
            BlockPos pos = LAST_CONTAINER_RC.get(id);
            if (ts == null || pos == null) return;
            if (System.currentTimeMillis() - ts > CONTAINER_RC_WINDOW_MS) {
                LAST_CONTAINER_RC.remove(id);
                LAST_CONTAINER_RC_AT.remove(id);
                return;
            }
            BlockEntity be = sp.level().getBlockEntity(pos);
            if (!(be instanceof Container c)) return;
            if (!CONTAINER_SNAPSHOT.containsKey(id) && CONTAINER_SNAPSHOT.size() >= MAX_CONTAINER_SNAPSHOTS) {
                evictOldestContainerSnapshot();
            }
            Map<Integer, ItemStack> snap = new HashMap<>();
            int size = Math.min(c.getContainerSize(), MAX_CONTAINER_SLOTS);
            for (int i = 0; i < size; i++) {
                snap.put(i, c.getItem(i).copy());
            }
            CONTAINER_SNAPSHOT.put(id, snap);
            CONTAINER_SNAPSHOT_POS.put(id, pos.immutable());
            CONTAINER_SNAPSHOT_AT.put(id, System.currentTimeMillis());
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onContainerOpen failed", t);
        }
    }

    /** Container closed -> compute delta vs open-time snapshot, submit deposit/withdraw rows. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close ev) {
        try {
            if (!(ev.getEntity() instanceof ServerPlayer sp)) return;
            UUID id = sp.getUUID();
            Map<Integer, ItemStack> snap = CONTAINER_SNAPSHOT.remove(id);
            BlockPos pos = CONTAINER_SNAPSHOT_POS.remove(id);
            CONTAINER_SNAPSHOT_AT.remove(id);
            LAST_CONTAINER_RC.remove(id);
            LAST_CONTAINER_RC_AT.remove(id);
            if (snap == null || pos == null) return;
            BlockEntity be = sp.level().getBlockEntity(pos);
            if (!(be instanceof Container c)) return;
            String worldId = WorldKey.of(sp.level());
            EventSubmitter s = sub();
            if (s == null) return;
            int size = Math.min(c.getContainerSize(), MAX_CONTAINER_SLOTS);
            boolean nbtOn = persistNbt();
            HolderLookup.Provider registries = nbtOn ? sp.level().registryAccess() : null;
            for (int slot = 0; slot < size; slot++) {
                ItemStack before = snap.getOrDefault(slot, ItemStack.EMPTY);
                ItemStack after = c.getItem(slot);
                int beforeCount = before.isEmpty() ? 0 : before.getCount();
                int afterCount = after.isEmpty() ? 0 : after.getCount();
                String itemId = !before.isEmpty() ? itemId(before) : (!after.isEmpty() ? itemId(after) : null);
                if (itemId == null) continue;
                int delta = afterCount - beforeCount;
                if (delta == 0) continue;
                byte[] itemNbt = null;
                if (nbtOn) {
                    // delta > 0 means deposit — the "after" side carries the NBT
                    // for what was added; delta < 0 means withdraw — the "before"
                    // side carries the NBT for what was removed.
                    ItemStack src = delta > 0 ? after : before;
                    itemNbt = NbtCapture.itemStack(src, registries);
                }
                if (itemNbt != null) {
                    s.submitContainerChange(sp.getUUID(), sp.getName().getString(), worldId,
                            pos.getX(), pos.getY(), pos.getZ(), itemId, delta, null, itemNbt);
                } else {
                    s.submitContainerChange(sp.getUUID(), sp.getName().getString(), worldId,
                            pos.getX(), pos.getY(), pos.getZ(), itemId, delta, null);
                }
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onContainerClose failed", t);
        }
    }

    /** Left-click block -> if inspecting, cancel + lookup. */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock ev) {
        try {
            Player p = ev.getEntity();
            if (p == null) return;
            if (!Inspector.isInspecting(p.getUUID())) return;
            ev.setCanceled(true);
            Guardian g = g();
            if (g == null) return;
            BlockPos pos = ev.getPos();
            if (p instanceof ServerPlayer sp) {
                final MinecraftServer server = sp.getServer();
                final String worldId = WorldKey.of((Level) ev.getLevel());
                final int x = pos.getX(), y = pos.getY(), z = pos.getZ();
                WORKER.submit(() -> {
                    try {
                        List<String> lines = g.lookupAtPos(worldId, x, y, z, 10, System.currentTimeMillis());
                        if (server != null) {
                            server.execute(() -> {
                                for (String line : lines) {
                                    sp.sendSystemMessage(ChatRenderer.primary(g.theme(), line));
                                }
                            });
                        }
                    } catch (Throwable t) {
                        LOG.warn(Guardian.MARKER, "inspector lookup failed at {} {},{},{}", worldId, x, y, z, t);
                        if (server != null) {
                            server.execute(() -> sp.sendSystemMessage(ChatRenderer.error(g.theme(),
                                    "[VonixGuardian] Inspect lookup error: " + t.getMessage())));
                        }
                    }
                });
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLeftClickBlock failed", t);
        }
    }

    // ====================================================================== pistons

    /** Piston pre-event -> PISTON_EXTEND/RETRACT. */
    @SubscribeEvent
    public static void onPistonPre(PistonEvent.Pre ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            BlockPos pos = ev.getPos();
            String worldId = WorldKey.of((Level) ev.getLevel());
            BlockState state = ev.getState();
            String blockId = blockId(state);
            if (ev.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND) {
                s.submitPistonExtend(null, Sentinel.PISTON, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), blockId, Sentinel.PISTON);
            } else if (ev.getPistonMoveType() == PistonEvent.PistonMoveType.RETRACT) {
                s.submitPistonRetract(null, Sentinel.PISTON, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), blockId, Sentinel.PISTON);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onPistonPre failed", t);
        }
    }

    // ====================================================================== buckets

    // HISTORY(1.0.5): NeoForge 21.1 removed the public FillBucketEvent that 1.20.1 Forge
    // exposes — the bucket fill/empty path is now handled internally by BucketItem /
    // CommonHooks#onFillBucket without a fire-able event. CoreProtect-parity bucket
    // logging here requires either (a) a Mixin into BucketItem#use, or (b) listening
    // to BlockEvent.FluidPlaceBlockEvent + a UseItemOnBlockEvent pre-check. Deferred.

    // ====================================================================== hanging entities

    /**
     * W2-02 / A10: HANGING_PLACE submit path.
     * <p>NeoForge has no dedicated {@code HangingPlaceEvent} (that's a Bukkit API).
     * We observe hanging entities being added to the world via {@link EntityJoinLevelEvent}
     * and filter for {@link HangingEntity} subclasses (ItemFrame, GlowItemFrame,
     * Painting, LeashFenceKnotEntity). Attribution runs through the shared
     * damage-history resolver so player-placed frames get the placer as actor;
     * synthetic sources fall back to {@link Sentinel#UNKNOWN}.
     */
    @SubscribeEvent
    public static void onHangingJoinLevel(EntityJoinLevelEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Entity e = ev.getEntity();
            if (!(e instanceof HangingEntity)) return;
            // Loaded-from-disk = chunk reload, not a fresh place — skip to avoid a flood
            // of replayed placements every time the chunk paging cycles.
            if (ev.loadedFromDisk()) return;
            String type = EntitySentinel.of(e);
            BlockPos pos = e.blockPosition();
            Attribution attr = NeoForgeBootstrap.resolver != null
                    ? NeoForgeBootstrap.resolver.resolve(e, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
            s.submitHangingPlace(attr.actorUuid(),
                    attr.actorName() != null ? attr.actorName() : Sentinel.UNKNOWN,
                    WorldKey.of(ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    type, SourceTagger.tag(e));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onHangingJoinLevel failed", t);
        }
    }

    /**
     * W2-02 / A10: HANGING_BREAK submit path — player-caused only.
     * <p>{@link AttackEntityEvent} fires the tick a player hits an entity; if the
     * target is a {@link HangingEntity} vanilla will remove it on that same swing.
     * We log the break here with the attacking player as actor.
     * <p>HISTORY(A9-style): arrow / explosion / mob-caused hanging breaks need a mixin
     * on {@code HangingEntity#kill()} or {@code HangingEntity#hurt}; NeoForge
     * exposes no direct event for those paths. Deferred to the A9-mixin wave.
     */
    @SubscribeEvent
    public static void onAttackHanging(AttackEntityEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getEntity();
            Entity target = ev.getTarget();
            if (p == null || !(target instanceof HangingEntity)) return;
            String type = EntitySentinel.of(target);
            BlockPos pos = target.blockPosition();
            s.submitHangingBreak(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    type, SourceTagger.tag(target));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAttackHanging failed", t);
        }
    }

    // ====================================================================== vanilla block-state changes (Burn/Ignite/Fade/Form/Spread/Dispense/LeavesDecay)

    // HISTORY(A9-style, W2-02): NeoForge exposes NO fire-able events for
    //   BlockBurnEvent / BlockIgniteEvent / BlockFadeEvent / BlockFormEvent /
    //   BlockSpreadEvent / BlockDispenseEvent / LeavesDecayEvent — those are
    //   Bukkit/Spigot APIs; NeoForge only patches the underlying vanilla classes
    //   without an event surface. Wiring these on the NeoForge cell requires
    //   mixins:
    //     - BURN         → mixin on FireBlock#tick / #checkBurnOut around the
    //                       BlockPos removal (state == AIR before, was flammable).
    //     - IGNITE       → mixin on FireBlock#tick where a neighbour is set to
    //                       Blocks.FIRE.defaultBlockState() (also FlintAndSteelItem
    //                       and LightningBoltEntity#spawnFire paths).
    //     - FADE         → mixin on IceBlock#tick, FrostedIceBlock#slightlyMelt,
    //                       SnowLayerBlock#tick.
    //     - FORM         → mixin on ConcretePowderBlock, WaterBlock (freeze),
    //                       LavaBlock/FireBlock (obsidian/cobble/basalt), etc.
    //     - SPREAD       → mixin on SpreadingSnowyDirtBlock#tick (grass/mycelium),
    //                       MushroomBlock#growMushroom, VineBlock#tick.
    //     - DISPENSE     → mixin on DispenserBlock#dispenseFrom capturing the
    //                       ejected ItemStack and the origin BlockPos.
    //     - LEAVES_DECAY → mixin on LeavesBlock#randomTick where the block is
    //                       replaced with air after LeavesBlock#decaying returns true.
    //   All of these need a companion `guardian.mixins.json` and would be scheduled
    //   into a dedicated mixin wave (A9-mixin). Handlers are intentionally left
    //   unwired here so the audit stays honest: nothing "kind of" logs these.

    // ====================================================================== commands wiring

    /** Pending command dispatcher captured when commands fire before Guardian.boot.
     * Boot replays this once it has a live Guardian. */
    private static volatile com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> pendingDispatcher;

    /**
     * Register the {@code /vg} brigadier tree.
     * <p>
     * NeoForge fires {@code RegisterCommandsEvent} during datapack reload, BEFORE
     * any server-lifecycle event. If Guardian hasn't booted yet (cold start),
     * we stash the dispatcher and let {@link #replayDeferredCommands(Guardian)}
     * register the tree once Guardian is live.
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent ev) {
        try {
            Guardian g = g();
            if (g == null) {
                pendingDispatcher = ev.getDispatcher();
                LOG.info(Guardian.MARKER, "Deferred /vg command registration until Guardian.boot");
                return;
            }
            GuardianCommands.register(ev.getDispatcher(), g);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onRegisterCommands failed", t);
        }
    }

    /**
     * Replay the deferred command registration after Guardian boots.
     * Called from {@link NeoForgeBootstrap#onServerStarting}.
     */
    public static void replayDeferredCommands(Guardian g) {
        com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d = pendingDispatcher;
        if (d != null) {
            try {
                GuardianCommands.register(d, g);
                LOG.info(Guardian.MARKER, "/vg command tree registered (deferred from RegisterCommandsEvent)");
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "Deferred command registration failed", t);
            } finally {
                pendingDispatcher = null;
            }
        }
    }

    /** Clears the deferred dispatcher on server stop to avoid retaining the server graph
     *  across an in-JVM restart. Idempotent.
     *
     *  <p>v1.3.1 X6 (P3-1): also shuts down the static {@link #WORKER} executor. Pre-X6
     *  the worker was a static daemon that never terminated, leaking a thread across
     *  every in-JVM server restart (dev mode). WORKER is {@code static final} — once
     *  shut down it stays shut down for the JVM's lifetime; in production this is fine
     *  because {@code reset()} runs at server-stop and the JVM exits shortly after. In
     *  dev / test harnesses that do an in-JVM restart, the harness rebuilds the whole
     *  {@code NeoForgeEvents} initialization path via a fresh classloader, so a fresh
     *  WORKER is created for the new run.
     */
    public static void reset() {
        pendingDispatcher = null;
        try {
            if (!WORKER.isShutdown()) {
                WORKER.shutdown();
                if (!WORKER.awaitTermination(2L, java.util.concurrent.TimeUnit.SECONDS)) {
                    LOG.warn(Guardian.MARKER, "NeoForgeEvents.WORKER did not shut down in 2s; forcing shutdownNow");
                    WORKER.shutdownNow();
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            WORKER.shutdownNow();
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "NeoForgeEvents.WORKER shutdown raised", t);
        }
    }

    // ====================================================================== helpers

    private static String blockId(BlockState state) {
        try {
            ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    private static String itemId(ItemStack stack) {
        try {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
