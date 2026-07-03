/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityStruckByLightningEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import network.vonix.guardian.core.attribution.FluidSourceMemory;
import network.vonix.guardian.core.attribution.TntPrimeMemory;
import network.vonix.guardian.core.diagnostics.MixinHotEventFilter;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.IpHasher;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.core.filter.VanillaGrieferSet;
import network.vonix.guardian.mc.v1_20_1.common.ChatRenderer;
import network.vonix.guardian.mc.v1_20_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_20_1.common.GuardianCommands;
import network.vonix.guardian.mc.v1_20_1.common.Inspector;
import network.vonix.guardian.mc.v1_20_1.common.SourceTagger;
import network.vonix.guardian.mc.v1_20_1.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Static {@code @SubscribeEvent} handlers for Forge 1.20.1.
 *
 * <p>Differences from 1.21.1 NeoForge:
 * <ul>
 *   <li>{@code LivingDamageEvent} is one class — no {@code .Pre} suffix.</li>
 *   <li>{@code PlayerEvent.ItemPickupEvent} replaces {@code ItemEntityPickupEvent}.</li>
 *   <li>{@code Explosion.getPosition()} replaces {@code center()}.</li>
 * </ul>
 */
public final class ForgeEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeEvents.class);

    // v1.3.0 W2: de-boxed spawn-limit throttle. Previous Map<String,Long> auto-boxed a
    // fresh Long on every put(...) — several MB/s of short-lived garbage on modded
    // shards with mob-farm-heavy chunks (piglin bartering, drowned trident farms, etc.).
    // AtomicLong lets us update in place with a plain volatile store on the hot path.
    private static final Map<String, AtomicLong> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    /** Daemon worker for off-tick JDBC (inspector lookups). */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VonixGuardian-ForgeEvents-Worker");
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
     * per open. Now we only run the full TTL scan every
     * {@link #CONTAINER_CLEANUP_EVERY} opens OR when the map exceeds
     * {@link #MAX_CONTAINER_SNAPSHOTS} (bounded-work fast-path eviction).
     */
    private static final java.util.concurrent.atomic.AtomicInteger CONTAINER_CLEANUP_TICK
            = new java.util.concurrent.atomic.AtomicInteger();
    /** Run cleanupContainerSnapshots' full TTL scan every N opens; power-of-two so we can mask. */
    private static final int CONTAINER_CLEANUP_EVERY = 32;

    /**
     * v1.3.0 W3: pooled scratch buffer for onExplosionDetonate capture. One
     * per server thread — reused across explosions to avoid re-allocating
     * 3× int[] + 1× String[] per detonation.
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

    private ForgeEvents() {
        // utility
    }

    private static Guardian g() {
        return VonixGuardianForge.guardian();
    }

    private static EventSubmitter sub() {
        Guardian g = g();
        return g == null ? null : g.submitter();
    }

    private static GuardianConfig cfg() {
        Guardian g = g();
        return g == null ? null : g.config();
    }

    /** v1.3.2 Y1 config-gated NBT persist flag. */
    private static boolean persistNbt() {
        GuardianConfig c = cfg();
        return c != null && c.storage() != null && c.storage().persistNbt();
    }

    // ====================================================================== blocks

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
            // v1.3.2 Y1: NBT capture on server thread BEFORE the break resolves.
            if (persistNbt()) {
                Level lvl = (Level) ev.getLevel();
                String stateProps = NbtCapture.blockStateProps(state);
                BlockEntity be = lvl.getBlockEntity(pos);
                byte[] beNbt = be == null ? null : NbtCapture.blockEntity(be);
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
            Attribution attr = ForgeBootstrap.resolver != null
                    ? ForgeBootstrap.resolver.resolve(e, System.currentTimeMillis())
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
     * Explosion detonate — v1.3.0 W3: server thread does the per-pos
     * {@code getBlockState} + registry key lookup into pooled scratch arrays,
     * then hands the {@link StringBuilder} join + queue enqueue to
     * {@link network.vonix.guardian.core.event.ExplosionJoinWorker}. A 5,000-block
     * TNT chain no longer builds a 4 KiB {@link StringBuilder} on the tick;
     * the worker splits the join into chunked {@code EXPLOSION} rows.
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
            int idx = 0;
            for (BlockPos p : affected) {
                scratch.xs[idx] = p.getX();
                scratch.ys[idx] = p.getY();
                scratch.zs[idx] = p.getZ();
                scratch.ids[idx] = blockId(ev.getLevel().getBlockState(p));
                idx++;
            }
            Entity source = ev.getExplosion().getDirectSourceEntity();
            // v1.3.1 X7: PrimedTnt loses its igniter by the time
            // ExplosionEvent.Detonate fires. Consult TntPrimeMemory
            // (populated by Y2 event-bus handlers onTntRightClickPrime /
            // the entity's block position before falling back to the
            // resolver. Closes CoreProtect-parity gap G-CP-2.
            String worldIdForPrime = WorldKey.of((Level) ev.getLevel());
            Attribution attr = null;
            if (source instanceof net.minecraft.world.entity.item.PrimedTnt) {
                Guardian gEarly = g();
                if (gEarly != null) {
                    BlockPos originPos = source.blockPosition();
                    attr = network.vonix.guardian.core.attribution.UniversalAttribution
                            .resolveTntPrime(gEarly.tntPrimeMemory(), worldIdForPrime,
                                    originPos.getX(), originPos.getY(), originPos.getZ(),
                                    Sentinel.TNT);
                }
            }
            if (attr == null) {
                attr = (source != null && ForgeBootstrap.resolver != null)
                        ? ForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())
                        : Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            Vec3 c = ev.getExplosion().getPosition();
            BlockPos center = BlockPos.containing(c);
            String worldId = WorldKey.of((Level) ev.getLevel());
            String sourceTag = source != null ? SourceTagger.tag(source) : Sentinel.EXPLOSION;
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

    /** 1.20.1 has the single-class {@code LivingDamageEvent} (no .Pre/.Post). */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent ev) {
        try {
            if (ForgeBootstrap.damageHistory == null) return;
            Entity src = ev.getSource().getEntity();
            LivingEntity victim = ev.getEntity();
            if (victim == null) return;
            if (src instanceof Player p) {
                ForgeBootstrap.damageHistory.record(victim.getUUID(), p.getUUID(),
                        System.currentTimeMillis());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDamage failed", t);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            LivingEntity victim = ev.getEntity();
            if (victim == null) return;

            Entity killer = ev.getSource().getEntity();
            Attribution attr;
            if (killer != null && ForgeBootstrap.resolver != null) {
                attr = ForgeBootstrap.resolver.resolve(killer, System.currentTimeMillis());
            } else {
                attr = Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            BlockPos pos = victim.blockPosition();

            String entityType = EntitySentinel.of(victim);

            // v1.3.2 Y1: capture the victim's persistent NBT BEFORE death resolves.

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
            if (ForgeBootstrap.damageHistory != null) {
                ForgeBootstrap.damageHistory.forget(victim.getUUID());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDeath failed", t);
        }
    }

    // ====================================================================== entities

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

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> ENTITY_JOIN_WARN_LIMIT =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ====================================================================== sessions

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
     * cancel it, AND we still receive cancelled events from earlier listeners. Forge's
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

    @SubscribeEvent
    public static void onCommand(CommandEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            ParseResults<CommandSourceStack> res = ev.getParseResults();
            if (res == null) return;
            CommandSourceStack src = res.getContext().getSource();
            // v1.3.6 CC2 (P1-5): server-side gate. CommandEvent fires on both
            // logical sides on integrated servers; a client-side dispatch has
            // src.getLevel() == null and we would blow up on the WorldKey.of
            // call below. Also guard the server-thread invariant so an off-
            // thread dispatcher (rare, but Sinytra Connector has done this)
            // does not race the submitter.
            if (src == null || src.getLevel() == null) return;
            if (src.getServer() == null || !src.getServer().isSameThread()) return;
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

            // v1.3.2 Y1: item toss NBT — names/enchants/damage on drop.

            byte[] itemNbt = persistNbt() ? NbtCapture.itemStack(stack) : null;

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

    /** 1.20.1 Forge uses {@code PlayerEvent.ItemPickupEvent}. */
    @SubscribeEvent
    public static void onItemPickup(PlayerEvent.ItemPickupEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getEntity();
            ItemStack stack = ev.getStack();
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
     * container-entity detection and CLICK submit. Old path called
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


    /**
     * v1.3.0 W3: amortized cleanup. Old path scanned the full
     * {@code CONTAINER_SNAPSHOT_AT} map on every container open. Now we run
     * the TTL scan only every {@link #CONTAINER_CLEANUP_EVERY} opens, PLUS an
     * immediate fast-path eviction when the map exceeds
     * {@link #MAX_CONTAINER_SNAPSHOTS} (bounded work per open).
     */
    private static void cleanupContainerSnapshots() {
        // Fast-path: bounded eviction if we're over cap.
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

    // ====================================================================== containers

    /** Container opened -> if a recent RC at a Container BE matches, snapshot contents for close-time diff. */
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

    /** Container closed -> diff snapshot vs current, submit per-slot deposits/withdrawals. */
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
                    // delta > 0 = deposit (after carries NBT); delta < 0 = withdraw (before carries NBT).
                    ItemStack src = delta > 0 ? after : before;
                    itemNbt = NbtCapture.itemStack(src);
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

    // ====================================================================== pistons

    @SubscribeEvent
    public static void onPistonPre(PistonEvent.Pre ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            BlockPos pos = ev.getPos();
            String worldId = WorldKey.of((Level) ev.getLevel());
            BlockState state = ev.getState();
            String blockId = blockId(state);
            // PistonEvent.PistonMoveType: EXTEND fires on extend, RETRACT fires on retract.
            // Previously the else-branch was dead code because every Pre fires both — fixed
            // so RETRACT actually emits its own action type for parity with CoreProtect.
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

    /**
     * P0-3: bucket fill / empty parity with CoreProtect.
     * {@link FillBucketEvent} fires for BOTH directions; distinguish by inspecting the
     * stack the player is holding when the event fires:
     *  - empty bucket in hand → fill (water/lava/powder_snow into bucket)
     *  - filled bucket in hand → empty (place liquid in world)
     * Coordinates resolve from the raytrace target — that's the affected block.
     */
    @SubscribeEvent
    public static void onFillBucket(FillBucketEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Player p = ev.getEntity();
            if (p == null) return;
            HitResult target = ev.getTarget();
            if (!(target instanceof BlockHitResult bhr)) return;
            BlockPos pos = bhr.getBlockPos();
            ItemStack held = ev.getEmptyBucket();
            Item heldItem = held != null ? held.getItem() : null;
            String heldId = heldItem != null
                    ? BuiltInRegistries.ITEM.getKey(heldItem).toString()
                    : "minecraft:bucket";
            String worldId = WorldKey.of(p.level());
            // Heuristic: stack passed via getEmptyBucket() is what the player CURRENTLY
            // holds. If it's a plain bucket they're filling; otherwise they're emptying.
            if (heldItem != null && "minecraft:bucket".equals(heldId)) {
                // FILL — block becomes air, bucket gains content
                BlockState state = p.level().getBlockState(pos);
                String fluidId = blockId(state);
                s.submitBucketFill(p.getUUID(), p.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), fluidId, null);
            } else {
                // EMPTY — bucket loses content, block becomes liquid
                // Use the held-bucket id minus _bucket suffix as the fluid hint.
                String fluid = heldId.endsWith("_bucket")
                        ? "minecraft:" + heldId.substring(heldId.lastIndexOf(':') + 1).replace("_bucket", "")
                        : heldId;
                s.submitBucketEmpty(p.getUUID(), p.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), fluid, null);
                // v1.3.1 X3: seed the 2-min traceback so downstream FLUID_FLOW rows attribute back.
                try {
                    Guardian gg = g();
                    if (gg != null) {
                        network.vonix.guardian.core.attribution.FluidSourceMemory mem = gg.fluidSourceMemory();
                        if (mem != null) {
                            mem.recordBucketEmpty(worldId, pos.getX(), pos.getY(), pos.getZ(),
                                    p.getUUID(), p.getName().getString(),
                                    System.currentTimeMillis());
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onFillBucket failed", t);
        }
    }

    // ====================================================================== hanging entities

    /**
     * W2-02 / A10: HANGING_PLACE submit path.
     * <p>Forge has no dedicated {@code HangingPlaceEvent} (that's a Bukkit API). We
     * observe hanging entities being added to the world via {@link EntityJoinLevelEvent}
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
            Attribution attr = ForgeBootstrap.resolver != null
                    ? ForgeBootstrap.resolver.resolve(e, System.currentTimeMillis())
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
     * on {@code HangingEntity#kill()} or on {@code HangingEntity#hurt}; Forge/NeoForge
     * expose no direct event for those paths. Deferred to the A9-mixin wave.
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
    //
    // v1.3.3 Z2 — Forge natural-block event-bus fallback.
    //
    // Round-3 audit P1-A: on the 3 Forge cells (1.18.2/1.19.2/1.20.1), the
    // FireBlock / IceBlock / LeavesBlock / SpreadingSnowyDirtBlock /
    // DispenserBlock mixins existed as source files under forge/mixin/ but
    // were dormant — no vg.mixins.json wiring — so BURN, IGNITE, FADE, FORM,
    // SPREAD, LEAVES_DECAY, DISPENSE actions were never emitted on Forge.
    //
    // Z2 follows the Y2 pattern (Forge event bus instead of mixin config).
    // Forge exposes NO first-class events for these transitions (they are
    // Bukkit/Spigot-only APIs), but BlockEvent.NeighborNotifyEvent fires
    // AFTER Level.updateNeighborsAt, which every vanilla setBlock*/removeBlock
    // path invokes. By caching a bounded LRU of "last observed BlockState" per
    // (worldId, packedPos) for hot natural-block positions, we can compare
    // pre-state vs post-state inside NeighborNotifyEvent and classify the
    // transition into the correct ActionType:
    //   fire  -> air/other      => BURN
    //   air/other -> fire       => IGNITE
    //   ice -> water/air        => FADE  (snow layer, frosted ice included)
    //   powder -> concrete      => FORM  (also lava-water -> obsidian/cobble/basalt)
    //   dirt -> grass/mycelium  => SPREAD
    //   leaves -> air           => LEAVES_DECAY
    //
    // ACKNOWLEDGED COVERAGE GAPS (see docs/PERF-NOTES-1.3.3.md § Z2):
    //   * DISPENSE cannot be caught via the Forge event bus. DispenserBlock
    //     #dispenseFrom is a private static funnel with no companion event on
    //     any of 1.18.2 / 1.19.2 / 1.20.1. NeighborNotifyEvent fires on the
    //     redstone-pulse neighbor update but does not carry the ejected
    //     ItemStack or origin BlockPos context. Z2 documents the gap explicitly
    //     rather than fake coverage; Fabric+NeoForge already log DISPENSE via
    //     their wired mixins.
    //   * BURN/IGNITE via LightningBoltEntity#spawnFire deep call chain is
    //     already covered by the Y2 onEntityStruckByLightning handler above
    //     (1-tick deferred 3x3 scan). Z2 adds coverage for player-lit,
    //     natural-spread, and burn-out paths via the neighbor-notify diff.
    //   * FLINT_AND_STEEL right-click that places fire on the CLICKED face is
    //     also caught by the pre/post diff below because Level.setBlock invokes
    //     updateNeighborsAt with the fire block as ev.getState().
    //
    // The cache is bounded (NATURAL_BLOCK_CACHE_MAX = 4096) and only tracks
    // positions where we've observed a "hot" natural block (fire, ice, leaves,
    // snow layer, ice variants, powder, dirt/grass/mycelium/podzol). The LRU
    // dropping stanza keeps steady-state memory under ~1 MiB even during a
    // wildfire event.

    /**
     * Hot natural-block LRU cache — maps (worldId, packedPos) -> last observed
     * block registry id. Populated on first-touch when NeighborNotifyEvent
     * observes a hot natural block at ev.getPos(); consulted on every subsequent
     * NeighborNotifyEvent at the same position to classify the transition.
     *
     * <p>Access is single-threaded (server tick thread) via the neighbor-notify
     * handler; a ConcurrentHashMap is used defensively in case a mod off-threads
     * neighbor-update dispatches (Sinytra Connector has been observed doing this
     * on NeoForge 1.21.1 — this cell is 1.20.1 Forge which does not, but the
     * cheap safety is worth 0.1% throughput).</p>
     */
    private static final Map<NaturalKey, String> NATURAL_BLOCK_CACHE = new ConcurrentHashMap<>();

    /**
     * v1.3.6 CC2 (P2-7): composite key replacing the pre-1.3.6 packed-long
     * key. The old encoding XOR-mixed worldId.hashCode() (32 bits) with
     * x/y/z packed into 26/12/26 bits — a collision-prone scheme when
     * worldId hashes shared low-order bits with high-order X coordinates
     * (routinely hit at spawn on servers with mod-added dimensions).
     * A record with proper equals/hashCode makes collisions structurally
     * impossible without adding hot-path allocation cost — the record is
     * allocated once per NeighborNotifyEvent, well below GC noise.
     */
    private record NaturalKey(String worldId, int x, int y, int z) { }
    /** Hard cap on cache size; eviction is oldest-first via {@link java.util.Iterator#remove}. */
    private static final int NATURAL_BLOCK_CACHE_MAX = 4096;
    /** Rate-limit for Z2 error logging (per-throwable-class, 60 s). */
    private static final Map<String, Long> Z2_WARN_LIMIT = new ConcurrentHashMap<>();

    /** Packs (worldId hash, x, y, z) into a single long for cache key density. */
    private static NaturalKey naturalCacheKey(String worldId, BlockPos pos) {
        // v1.3.6 CC2 (P2-7): return a composite record instead of a packed
        // long — see NaturalKey javadoc for the collision analysis. Record
        // allocation is trivially cheap vs the (rare) cache miss cost.
        return new NaturalKey(worldId == null ? "" : worldId,
                pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Classify a natural block by registry id. Returns null for non-hot blocks
     * (which we do not cache or classify to keep the map bounded).
     *
     * <p>Categories:</p>
     * <ul>
     *   <li>{@code "fire"} — {@code minecraft:fire}, {@code minecraft:soul_fire}</li>
     *   <li>{@code "ice"} — {@code minecraft:ice}, {@code minecraft:frosted_ice},
     *       {@code minecraft:packed_ice}, {@code minecraft:blue_ice}, snow layer</li>
     *   <li>{@code "leaves"} — anything ending in {@code _leaves}</li>
     *   <li>{@code "powder"} — {@code *_concrete_powder}</li>
     *   <li>{@code "concrete"} — {@code *_concrete}</li>
     *   <li>{@code "grass"} — {@code minecraft:grass_block}, {@code minecraft:mycelium},
     *       {@code minecraft:podzol}</li>
     *   <li>{@code "dirt"} — {@code minecraft:dirt}, {@code minecraft:coarse_dirt},
     *       {@code minecraft:rooted_dirt}</li>
     *   <li>{@code "obsidian"} / {@code "cobblestone"} / {@code "stone"} / {@code "basalt"}
     *       — fluid-generator products (for FORM classification)</li>
     * </ul>
     */
    private static String naturalCategory(String blockId) {
        if (blockId == null) return null;
        // fire family
        if (blockId.equals("minecraft:fire") || blockId.equals("minecraft:soul_fire")) return "fire";
        // ice / snow family (all melt-to-water/air / form-from-water/freeze)
        if (blockId.equals("minecraft:ice") || blockId.equals("minecraft:frosted_ice")
                || blockId.equals("minecraft:packed_ice") || blockId.equals("minecraft:blue_ice")
                || blockId.equals("minecraft:snow") || blockId.equals("minecraft:snow_block")) return "ice";
        // leaves family (vanilla + mods that end in _leaves)
        if (blockId.endsWith("_leaves")) return "leaves";
        // concrete powder / concrete pair (form)
        if (blockId.endsWith("_concrete_powder")) return "powder";
        if (blockId.endsWith("_concrete")) return "concrete";
        // grass / mycelium / podzol (spread from dirt)
        if (blockId.equals("minecraft:grass_block") || blockId.equals("minecraft:mycelium")
                || blockId.equals("minecraft:podzol")) return "grass";
        if (blockId.equals("minecraft:dirt") || blockId.equals("minecraft:coarse_dirt")
                || blockId.equals("minecraft:rooted_dirt")) return "dirt";
        // fluid-generator products (form)
        if (blockId.equals("minecraft:obsidian")) return "obsidian";
        if (blockId.equals("minecraft:cobblestone")) return "cobblestone";
        if (blockId.equals("minecraft:stone")) return "stone";
        if (blockId.equals("minecraft:basalt")) return "basalt";
        // water/lava explicit — used as the "prev" for FORM classification
        if (blockId.equals("minecraft:water") || blockId.equals("minecraft:flowing_water")) return "water";
        if (blockId.equals("minecraft:lava") || blockId.equals("minecraft:flowing_lava")) return "lava";
        return null;
    }

    /**
     * Z2 — natural-block transition capture via NeighborNotifyEvent + LRU diff.
     *
     * <p>Fires from every {@code Level.updateNeighborsAt(BlockPos, Block)}. We
     * compare {@code ev.getState()} (the current block at {@code ev.getPos()})
     * against our last-observed snapshot at the same position; if the categories
     * disagree, we classify the transition and emit the corresponding action.
     * Then we update the cache to the current post-state so the next transition
     * on this pos measures against a fresh baseline.</p>
     *
     * <p>Non-hot blocks (anything not classified by {@link #naturalCategory})
     * are neither cached nor emitted — this keeps the map bounded to actually
     * interesting positions.</p>
     */
    @SubscribeEvent
    public static void onNaturalBlockNeighborNotify(BlockEvent.NeighborNotifyEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            BlockState cur = ev.getState();
            if (cur == null) return;
            Level level = (Level) ev.getLevel();
            if (!(level instanceof ServerLevel)) return;
            BlockPos pos = ev.getPos();
            String worldId = WorldKey.of(level);
            NaturalKey key = naturalCacheKey(worldId, pos);
            String curId = blockId(cur);
            String curCat = naturalCategory(curId);
            String prevId = NATURAL_BLOCK_CACHE.get(key);
            String prevCat = naturalCategory(prevId);

            if (prevId != null && !prevId.equals(curId)) {
                // classify a real transition (prev observed, differs from cur)
                classifyAndSubmit(s, worldId, pos, prevId, curId, prevCat, curCat);
            } else if (prevId == null && "fire".equals(curCat)) {
                // Fresh fire appearance with no prior cache entry -> IGNITE.
                // Matches pre-Z2 FireBlockMixin#onPlace(TAIL) semantics for
                // flint&steel / /setblock fire / lightning-spawned fire that
                // arrived without a prior neighbor-notify observation.
                s.submitIgnite(null, Sentinel.FIRE, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), curId, "#fire:ignite");
            }

            // Cache maintenance — only remember "primary hot" positions (fire,
            // ice, leaves, powder, concrete, grass, dirt). FORM-target categories
            // (obsidian/cobblestone/stone/basalt) are only meaningful as the
            // TARGET of a water/lava transition; caching a bare stone/obsidian
            // observation would waste memory since there's nothing to transition
            // FROM those without a prior fluid. Keep water/lava too so the
            // fluid->product transition can be detected.
            boolean cachePrimary = isPrimaryHot(curCat);
            if (cachePrimary) {
                NATURAL_BLOCK_CACHE.put(key, curId);
                enforceCacheBound();
            } else if (prevCat != null) {
                // Was hot, now non-hot: retain the successor so the next
                // transition on this position measures against a fresh baseline.
                NATURAL_BLOCK_CACHE.put(key, curId);
                enforceCacheBound();
            }
        } catch (Throwable t) {
            rateLimitedZ2Warn("onNaturalBlockNeighborNotify", t);
        }
    }

    /**
     * Whether a category is a "primary hot" natural block that we track
     * unconditionally. Non-primary categories (FORM products like stone /
     * obsidian / cobble / basalt) are only meaningful when a prior primary
     * hot block was observed at the position.
     */
    private static boolean isPrimaryHot(String cat) {
        if (cat == null) return false;
        return switch (cat) {
            case "fire", "ice", "leaves", "powder", "concrete", "grass", "dirt", "water", "lava" -> true;
            default -> false;
        };
    }

    /**
     * Classify a pre/post-state transition at a bounded set of hot positions
     * and emit the corresponding action-type submit.
     */
    private static void classifyAndSubmit(EventSubmitter s, String worldId, BlockPos pos,
                                          String prevId, String curId, String prevCat, String curCat) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        // BURN — fire -> air, or flammable -> air adjacent to fire (only fire->non-fire caught here)
        if ("fire".equals(prevCat) && !"fire".equals(curCat)) {
            // fire was extinguished / consumed -> burn-out row on the fire itself
            s.submitBurn(null, Sentinel.FIRE, worldId, x, y, z, prevId, "#fire:burnout");
            return;
        }
        // IGNITE — any -> fire (fresh fire block appeared)
        if (!"fire".equals(prevCat) && "fire".equals(curCat)) {
            s.submitIgnite(null, Sentinel.FIRE, worldId, x, y, z, curId, "#fire:ignite");
            return;
        }
        // FADE — ice/snow -> water/air (melt)
        if ("ice".equals(prevCat) && !"ice".equals(curCat)) {
            s.submitFade(null, "#natural:fade", worldId, x, y, z, prevId, "#natural:fade");
            return;
        }
        // FORM — powder -> concrete (concrete solidify)
        if ("powder".equals(prevCat) && "concrete".equals(curCat)) {
            s.submitForm(null, "#natural:form", worldId, x, y, z, curId, "#natural:form");
            return;
        }
        // FORM — water/lava -> obsidian/cobblestone/stone/basalt (fluid interaction)
        if (("water".equals(prevCat) || "lava".equals(prevCat)) &&
                ("obsidian".equals(curCat) || "cobblestone".equals(curCat)
                        || "stone".equals(curCat) || "basalt".equals(curCat))) {
            s.submitForm(null, "#natural:form", worldId, x, y, z, curId, "#natural:form");
            return;
        }
        // FORM — air/water -> ice/snow (freeze — reverse of fade)
        if (("water".equals(prevCat) || prevCat == null) && "ice".equals(curCat)) {
            s.submitForm(null, "#natural:form", worldId, x, y, z, curId, "#natural:form");
            return;
        }
        // SPREAD — dirt/coarse_dirt/rooted_dirt -> grass/mycelium/podzol
        if ("dirt".equals(prevCat) && "grass".equals(curCat)) {
            s.submitSpread(null, "#natural:spread", worldId, x, y, z, curId, "#natural:spread");
            return;
        }
        // LEAVES_DECAY — leaves -> air (or anything non-leaves)
        if ("leaves".equals(prevCat) && !"leaves".equals(curCat)) {
            s.submitLeavesDecay(null, "#natural:decay", worldId, x, y, z, prevId, "#natural:decay");
            return;
        }
        // Unclassified transition — do nothing (keeps audit honest).
    }

    /** Bounded LRU eviction — drops first insertion-ordered entry until under cap. */
    private static void enforceCacheBound() {
        if (NATURAL_BLOCK_CACHE.size() <= NATURAL_BLOCK_CACHE_MAX) return;
        // ConcurrentHashMap iteration order is unspecified but stable enough for
        // load-shedding under sustained pressure. We drop up to 32 entries per
        // overflow to amortise the scan cost.
        int drop = 32;
        Iterator<Map.Entry<NaturalKey, String>> it = NATURAL_BLOCK_CACHE.entrySet().iterator();
        while (it.hasNext() && drop > 0) {
            it.next();
            it.remove();
            drop--;
        }
    }

    private static void rateLimitedZ2Warn(String site, Throwable t) {
        long now = System.currentTimeMillis();
        String key = site + ":" + t.getClass().getName();
        Long last = Z2_WARN_LIMIT.get(key);
        if (last == null || now - last >= 60_000L) {
            Z2_WARN_LIMIT.put(key, now);
            LOG.warn(Guardian.MARKER, "{} failed", site, t);
        }
    }

    /**
     * Z2 — clear the natural-block cache on server shutdown so an in-JVM
     * restart does not retain stale (worldId, pos) -> blockId entries.
     * Also cleared in {@link #reset()} above (called by VonixGuardianForge on
     * shutdown).
     */
    public static void clearNaturalBlockCache() {
        NATURAL_BLOCK_CACHE.clear();
        Z2_WARN_LIMIT.clear();
    }

    // ACKNOWLEDGED GAP: DISPENSE is not covered by the Forge event bus on any
    // of 1.18.2 / 1.19.2 / 1.20.1. DispenserBlock#dispenseFrom is a private
    // funnel with no companion event carrying the ejected ItemStack + origin
    // BlockPos. NeighborNotifyEvent fires on the redstone-pulse neighbor update
    // but does not preserve the ejection context. The dormant DispenserBlockMixin
    // is deleted in this wave; a future wave that adds forge-side vg.mixins.json
    // wiring could restore mixin-based DISPENSE logging on Forge, but Z2's
    // scope is event-bus fallback only. See docs/PERF-NOTES-1.3.3.md § Z2.

    // ====================================================================== commands wiring

    private static volatile com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> pendingDispatcher;

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
     *  across an in-JVM restart. Idempotent. */
    public static void reset() {
        pendingDispatcher = null;
        clearNaturalBlockCache();
        clearHopperTracker();
    }

    // ====================================================================== v1.3.2 Y2 — event-bus parity handlers
    //
    // Y2 closes P0-2/P0-3 from the round-2 audit: X2/X3/X4/X7 mixins compile
    // dormant on Forge cells (no vg.mixins.json / mods.toml wiring). Rather
    // than adding forge-side mixin config (which forces us to validate SRG/
    // remapping descriptors across three Forge mapping vintages), we cover the
    // same event surface via Forge's public event bus. Coverage is a superset
    // of what the dormant mixins gave us on NeoForge/Fabric because Forge
    // exposes first-class events for several of these paths.

    /**
     * Y2 — Fluid flow attribution (X3 parity).
     *
     * <p>Forge has no {@code BlockFromToEvent}, but {@link BlockEvent.NeighborNotifyEvent}
     * fires from {@code Level#updateNeighborsAt} whenever a state changes and
     * notifies its neighbours. When the notifying block is a {@link LiquidBlock}
     * we scan the notified sides on the same tick and, if any neighbour is now
     * itself a fluid (i.e. the flow just extended into it), we emit a fluid-flow
     * row with attribution resolved through {@link FluidSourceMemory}.</p>
     *
     * <p>Also handles {@link BlockEvent.FluidPlaceBlockEvent} which fires
     * when fluid interaction creates a block (cobblestone / obsidian / basalt
     * generators). That transition is a place-side attribution — the block that
     * appeared is the flow source.</p>
     */
    @SubscribeEvent
    public static void onNeighborNotifyFluid(BlockEvent.NeighborNotifyEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            BlockState src = ev.getState();
            if (src == null || !(src.getBlock() instanceof LiquidBlock)) return;
            Level level = (Level) ev.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) return;
            BlockPos origin = ev.getPos();
            String worldId = WorldKey.of(level);
            long now = System.currentTimeMillis();
            for (Direction d : ev.getNotifiedSides()) {
                BlockPos nbr = origin.relative(d);
                FluidState fs = level.getFluidState(nbr);
                if (fs == null || fs.isEmpty()) continue;
                BlockState nbrState = level.getBlockState(nbr);
                if (!(nbrState.getBlock() instanceof LiquidBlock)) continue;
                String path;
                try {
                    ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fs.getType());
                    path = rl == null ? "water" : rl.getPath();
                } catch (Throwable t) {
                    path = "water";
                }
                String kind = path.contains("lava") ? "lava" : "water";
                String fluidBlockId = "minecraft:" + kind;
                String sourceTag = MixinHotEventFilter.PREFIX_FLUID + ":" + kind;
                UUID actorUuid = null;
                String actorName = Sentinel.FLUID;
                Guardian gg = g();
                if (gg != null) {
                    FluidSourceMemory mem = gg.fluidSourceMemory();
                    if (mem != null) {
                        FluidSourceMemory.Record rec = mem.lookup(worldId, nbr.getX(), nbr.getY(), nbr.getZ(), now);
                        if (rec != null && rec.actorUuid != null) {
                            actorUuid = rec.actorUuid;
                            actorName = rec.actorName != null ? rec.actorName : Sentinel.FLUID;
                        }
                    }
                }
                s.submitFluidFlow(actorUuid, actorName, worldId,
                        nbr.getX(), nbr.getY(), nbr.getZ(), fluidBlockId, sourceTag);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onNeighborNotifyFluid failed", t);
        }
    }

    /** Y2 — cobble/obsidian/basalt generator: fluid interaction creates a block. */
    @SubscribeEvent
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            BlockPos pos = ev.getPos();
            BlockState newState = ev.getNewState();
            if (newState == null) return;
            String worldId = WorldKey.of((Level) ev.getLevel());
            s.submitBlockPlace(null, Sentinel.FLUID, worldId,
                    pos.getX(), pos.getY(), pos.getZ(), blockId(newState),
                    MixinHotEventFilter.PREFIX_FLUID + ":place");
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onFluidPlaceBlock failed", t);
        }
    }

    /**
     * Y2 — TNT-prime capture, player right-click path (X7 parity).
     *
     * <p>Fires on {@link PlayerInteractEvent.RightClickBlock} when the player
     * targets a TNT block. Records the actor into {@link TntPrimeMemory} at the
     * block position; {@code onExplosionDetonate} consumes it.</p>
     */
    @SubscribeEvent
    public static void onTntRightClickPrime(PlayerInteractEvent.RightClickBlock ev) {
        try {
            Player p = ev.getEntity();
            if (p == null) return;
            BlockPos pos = ev.getPos();
            Level level = (Level) ev.getLevel();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof TntBlock)) return;
            Guardian gg = g();
            if (gg == null) return;
            TntPrimeMemory mem = gg.tntPrimeMemory();
            if (mem == null) return;
            long now = System.currentTimeMillis();
            mem.record(WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                    TntPrimeMemory.PrimeRecord.player(p.getUUID(),
                            p.getName().getString(), now));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onTntRightClickPrime failed", t);
        }
    }

    /**
     * Y2 — TNT-prime capture, projectile path (X7 parity).
     *
     * <p>Fires when a projectile impacts a block. If the block hit is TNT and
     * the projectile is a burning arrow (or otherwise on fire), record the
     * shooter as the priming actor.</p>
     */
    @SubscribeEvent
    public static void onProjectileImpactTnt(ProjectileImpactEvent ev) {
        try {
            Projectile proj = ev.getProjectile();
            HitResult hit = ev.getRayTraceResult();
            if (proj == null || !(hit instanceof BlockHitResult bhr)) return;
            if (!proj.isOnFire() && !(proj instanceof AbstractArrow arr && arr.isOnFire())) return;
            Level level = proj.level();
            if (level == null) return;
            BlockPos pos = bhr.getBlockPos();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof TntBlock)) return;
            Entity owner = proj.getOwner();
            Guardian gg = g();
            if (gg == null) return;
            TntPrimeMemory mem = gg.tntPrimeMemory();
            if (mem == null) return;
            long now = System.currentTimeMillis();
            if (owner instanceof Player p) {
                mem.record(WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                        TntPrimeMemory.PrimeRecord.player(p.getUUID(),
                                p.getName().getString(), now));
            } else {
                mem.record(WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                        TntPrimeMemory.PrimeRecord.fire(null, Sentinel.FIRE, now));
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onProjectileImpactTnt failed", t);
        }
    }

    /**
     * Y2 — TNT-prime capture, PrimedTnt-entity join (X7 belt-and-braces).
     *
     * <p>Catches modded TNT variants that spawn {@link PrimedTnt} directly
     * without going through {@code TntBlock#explode}. When Forge's
     * {@code getOwner()} returns a Player we record it.</p>
     */
    @SubscribeEvent
    public static void onPrimedTntJoin(EntityJoinLevelEvent ev) {
        try {
            Entity e = ev.getEntity();
            if (!(e instanceof PrimedTnt tnt)) return;
            if (ev.loadedFromDisk()) return;
            LivingEntity owner = tnt.getOwner();
            if (!(owner instanceof Player p)) return;
            Guardian gg = g();
            if (gg == null) return;
            TntPrimeMemory mem = gg.tntPrimeMemory();
            if (mem == null) return;
            BlockPos pos = tnt.blockPosition();
            long now = System.currentTimeMillis();
            mem.record(WorldKey.of(tnt.level()), pos.getX(), pos.getY(), pos.getZ(),
                    TntPrimeMemory.PrimeRecord.player(p.getUUID(),
                            p.getName().getString(), now));
        } catch (Throwable ignored) {
            // Silent — this fires for every entity spawn, so a warn-per-error would flood.
        }
    }

    /** Y2 — Nether portal frame creation (X4 parity). */
    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            BlockPos pos = ev.getPos();
            BlockState state = ev.getState();
            String worldId = WorldKey.of((Level) ev.getLevel());
            s.submitBlockPlace(null, Sentinel.PORTAL, worldId,
                    pos.getX(), pos.getY(), pos.getZ(),
                    state != null ? blockId(state) : "minecraft:nether_portal",
                    Sentinel.PORTAL);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onPortalSpawn failed", t);
        }
    }

    /**
     * Y2 — Lightning strike fire attribution (X2 parity for LightningBolt.spawnFire).
     *
     * <p>Fires on {@link EntityStruckByLightningEvent}. We scan the 3x3 column
     * around the struck position for {@code minecraft:fire} blocks that would
     * have been created by {@code LightningBolt.spawnFire}. This is a lossy but
     * pragmatic replacement — the mixin caught the exact write; the event fires
     * BEFORE the fire is placed, so we defer capture by one server tick using
     * the level's server executor.</p>
     */
    @SubscribeEvent
    public static void onEntityStruckByLightning(EntityStruckByLightningEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Entity struck = ev.getEntity();
            LightningBolt bolt = ev.getLightning();
            if (bolt == null) return;
            Level level = bolt.level();
            if (!(level instanceof ServerLevel serverLevel)) return;
            final BlockPos origin = struck != null ? struck.blockPosition() : bolt.blockPosition();
            final String worldId = WorldKey.of(level);
            // Defer one tick — LightningBolt.spawnFire runs after the event.
            serverLevel.getServer().execute(() -> {
                try {
                    EventSubmitter s2 = sub();
                    if (s2 == null) return;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos p = origin.offset(dx, 0, dz);
                            BlockState st = serverLevel.getBlockState(p);
                            if (st != null && st.getBlock() == Blocks.FIRE) {
                                s2.submitBlockPlace(null, EntitySentinel.SRC_LIGHTNING, worldId,
                                        p.getX(), p.getY(), p.getZ(), "minecraft:fire",
                                        EntitySentinel.SRC_LIGHTNING);
                            }
                        }
                    }
                } catch (Throwable t2) {
                    LOG.warn(Guardian.MARKER, "onEntityStruckByLightning deferred scan failed", t2);
                }
            });
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onEntityStruckByLightning failed", t);
        }
    }

    /**
     * Y2 — falling-block spawn attribution (X2 parity for FallingBlock.tick).
     *
     * <p>{@link FallingBlockEntity} spawning represents gravity converting a
     * placed block into an entity (sand/gravel/anvil falling). We log the origin
     * as an entity-change-block break — the block at the spawn position was
     * removed by gravity. The corresponding land-place is handled by
     * {@link BlockEvent.EntityPlaceEvent} which already fires with the
     * FallingBlockEntity as the placing entity.</p>
     */
    @SubscribeEvent
    public static void onFallingBlockJoin(EntityJoinLevelEvent ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            Entity e = ev.getEntity();
            if (!(e instanceof FallingBlockEntity fbe)) return;
            if (ev.loadedFromDisk()) return;
            BlockState fallen = fbe.getBlockState();
            if (fallen == null) return;
            BlockPos pos = fbe.blockPosition();
            String worldId = WorldKey.of(ev.getLevel());
            s.submitEntityChangeBlock(null, EntitySentinel.SRC_GRAVITY, worldId,
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(fallen), "minecraft:air", EntitySentinel.SRC_GRAVITY);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onFallingBlockJoin failed", t);
        }
    }

    /**
     * Y2 — dragon &amp; silverfish block-change tick surface (X2 parity).
     *
     * <p>{@link LivingDestroyBlockEvent} already covers the ravager and most
     * dragon paths but MISSES {@code EnderDragon.checkWalls} and
     * {@code Silverfish$SilverfishMergeWithStoneGoal.start}. Those methods
     * remove/replace blocks without firing {@code LivingDestroyBlockEvent}.
     * We approximate by tick-hooking these specific entity types: on each tick
     * we compare a small position window in front of them to a per-entity
     * previous snapshot and emit a change row when it differs.</p>
     *
     * <p>To keep cost bounded we only sample once every {@link #LIVING_TICK_SAMPLE_INTERVAL}
     * ticks per entity and cap the tracked-entity map at
     * {@link #LIVING_TICK_MAX_TRACKED}.</p>
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent ev) {
        try {
            LivingEntity e = ev.getEntity();
            if (e == null) return;
            if (!(e instanceof EnderDragon) && !(e instanceof Silverfish)
                    && !(e instanceof net.minecraft.world.entity.animal.SnowGolem)) return;
            Level level = e.level();
            if (!(level instanceof ServerLevel)) return;
            long tick = e.tickCount;
            if ((tick & (LIVING_TICK_SAMPLE_INTERVAL - 1)) != 0) return;
            String sourceTag;
            if (e instanceof EnderDragon) sourceTag = EntitySentinel.SRC_ENDER_DRAGON;
            else if (e instanceof Silverfish) sourceTag = EntitySentinel.SRC_SILVERFISH;
            else sourceTag = EntitySentinel.SRC_SNOW_GOLEM;
            String actorName = sourceTag;
            EventSubmitter s = sub();
            if (s == null) return;
            String worldId = WorldKey.of(level);
            BlockPos entPos = e.blockPosition();
            java.util.Map<Long, String> prevSnap = LIVING_BLOCK_SNAPSHOT.computeIfAbsent(e.getUUID(),
                    k -> new java.util.concurrent.ConcurrentHashMap<>());
            if (LIVING_BLOCK_SNAPSHOT.size() > LIVING_TICK_MAX_TRACKED) {
                // fast-path bounded eviction: drop an entry
                java.util.Iterator<UUID> it = LIVING_BLOCK_SNAPSHOT.keySet().iterator();
                if (it.hasNext()) {
                    UUID drop = it.next();
                    if (!drop.equals(e.getUUID())) it.remove();
                }
            }
            int r = e instanceof EnderDragon ? 3 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos p = entPos.offset(dx, dy, dz);
                        long key = p.asLong();
                        BlockState cur = level.getBlockState(p);
                        String curId = cur != null ? blockId(cur) : "minecraft:air";
                        String prev = prevSnap.get(key);
                        if (prev == null) {
                            prevSnap.put(key, curId);
                            continue;
                        }
                        if (!prev.equals(curId)) {
                            s.submitEntityChangeBlock(null, actorName, worldId,
                                    p.getX(), p.getY(), p.getZ(),
                                    prev, curId, sourceTag);
                            prevSnap.put(key, curId);
                        }
                    }
                }
            }
            // Bound snapshot size per entity
            if (prevSnap.size() > 512) {
                prevSnap.clear();
            }
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            String key = t.getClass().getName();
            Long last = LIVING_TICK_WARN_LIMIT.get(key);
            if (last == null || now - last >= 60_000L) {
                LIVING_TICK_WARN_LIMIT.put(key, now);
                LOG.warn(Guardian.MARKER, "onLivingTick failed", t);
            }
        }
    }

    /** Sample interval (power-of-two) for {@link #onLivingTick}. */
    private static final int LIVING_TICK_SAMPLE_INTERVAL = 8;
    /** Hard cap on tracked living entities to bound memory. */
    private static final int LIVING_TICK_MAX_TRACKED = 64;
    /** Per-entity block-state snapshot (position -> registry id). */
    private static final Map<UUID, Map<Long, String>> LIVING_BLOCK_SNAPSHOT =
            new ConcurrentHashMap<>();
    /** Rate-limit for onLivingTick error logging. */
    private static final Map<String, Long> LIVING_TICK_WARN_LIMIT = new ConcurrentHashMap<>();


    // ====================================================================== v1.3.3 Z3 — Forge hopper + /fill //setblock event-bus fallback
    //
    // Round-3 audit finding P1-B and P1-C:
    //   * HopperBlockEntityMixin.java (Z3 territory) called ForgeMixinBridge.hopperPush/Pull,
    //     but with no vg.mixins.json wiring the injects never activate — hopper
    //     transfers produced zero audit rows on Forge.
    //   * FillCommandMixin.java + SetBlockCommandMixin.java existed but were
    //     equally dormant — /fill and /setblock produced no per-block audit rows.
    //
    // Forge has NO first-class event for HopperBlockEntity#tryMoveItems or for
    // per-block writes from /fill / /setblock. Z3 restores coverage via a
    // sampled hopper content-diff tracker + a CommandEvent-driven pre/post
    // snapshot for the two commands. Both are documented as best-effort:
    //
    // ACKNOWLEDGED COVERAGE GAPS (see docs/PERF-NOTES-1.3.3.md § Z3):
    //   * HOPPER: sampled at HOPPER_SAMPLE_PER_TICK=20 hoppers per level per tick.
    //     On shards with hopper-farm densities >2000 active hoppers/dimension
    //     the sampler will miss transfers between the un-sampled majority on
    //     any given tick, but will eventually catch every hopper within
    //     ceil(N/20) ticks. Ledger's mixin-based hook is exact per transfer;
    //     Z3's tracker is a diff-based sample that observes only the net
    //     content delta between two samples of the same slot — chained
    //     push+pull within the same sample window can cancel out and go
    //     unrecorded. Fabric + NeoForge continue to use their wired
    //     HopperBlockEntityMixin for exact per-transfer capture.
    //   * HOPPER: pre-existing hoppers loaded before the server-side tracker
    //     is initialised (i.e. before the first LevelTickEvent for that level)
    //     are picked up when their chunk first fires ChunkEvent.Load with a
    //     block-entity walk. Hoppers placed while the tracker was inactive
    //     (e.g. during config reload) are not seen until the next chunk load.
    //   * COMMAND: /fill regions larger than FILL_MAX_REGION_BLOCKS (32,768,
    //     the vanilla /fill hard cap) are skipped entirely to keep the pre/post
    //     snapshot bounded. Vanilla /fill itself rejects regions >32,768 so
    //     this is a no-op in practice; modded /fill variants that widen the
    //     cap are documented as uncovered.
    //   * COMMAND: /fill with mode=replace and a filter clause reads the same
    //     per-position break+place delta; we do not attempt to reproduce the
    //     filter semantics — we just diff pre vs post, which is what any
    //     rollback system needs.
    //   * COMMAND: pre/post-snapshot fires on the CURRENT server tick and
    //     deferred diff runs on server.execute() (next tick boundary). Vanilla
    //     /fill and /setblock complete synchronously within the command
    //     invocation, so this ordering is correct. A mod that delays the
    //     write to a later tick will observe the "post" state at execute time
    //     as still equal to pre, and the row will be omitted (safe under-log
    //     rather than false positive).

    /** Z3 — sample-per-tick cap for hopper content-diff. */
    private static final int HOPPER_SAMPLE_PER_TICK = 20;
    /** Z3 — hard cap on tracked hopper positions per level. */
    private static final int HOPPER_TRACKER_MAX_PER_LEVEL = 8192;
    /** Z3 — hard cap on /fill region size we will snapshot (vanilla limit). */
    private static final int FILL_MAX_REGION_BLOCKS = 32_768;
    /** Z3 — vanilla hopper slot count. */
    private static final int HOPPER_SLOTS = 5;

    /**
     * Z3 — Per-level tracked hopper packed-keys, iterated round-robin by the
     * per-tick sampler. Populated on {@code ChunkEvent.Load} block-entity walk
     * and on hopper block-place; drained on hopper break + hopper chunk-unload.
     */
    /**
     * v1.3.6 CC2 (P1-1): per-level tracked hopper packed-keys.
     *
     * <p>Pre-1.3.6 used {@code ConcurrentLinkedDeque<Long>}, whose
     * {@code remove(Object)} is O(n). Under a dense hopper-farm topology
     * (thousands of hoppers per dimension) both {@link #z3Unregister} on
     * a hopper break AND the {@link #onChunkUnloadDropHoppers} bulk drain
     * paid O(hoppers × unloaded_hoppers) on the server thread.</p>
     *
     * <p>Replaced with a {@code LinkedHashSet<Long>} guarded by a per-level
     * lock (see {@link #hopperLockFor}): {@code remove(Object)} is O(1),
     * iteration retains insertion order for the round-robin sampler, and
     * the guard is uncontended in practice because all hopper events are
     * dispatched on the server thread.</p>
     */
    private static final Map<String, java.util.LinkedHashSet<Long>> HOPPER_POS_BY_LEVEL =
            new ConcurrentHashMap<>();
    /** v1.3.6 CC2 (P1-1): per-level guard for {@link #HOPPER_POS_BY_LEVEL}. */
    private static final Map<String, Object> HOPPER_LOCK_BY_LEVEL = new ConcurrentHashMap<>();

    /** v1.3.6 CC2 (P1-1): fetch or lazily create the per-level lock. */
    private static Object hopperLockFor(String worldId) {
        return HOPPER_LOCK_BY_LEVEL.computeIfAbsent(worldId, w -> new Object());
    }
    /**
     * Z3 — Per-position slot-contents snapshot. Value is a length-5 array of
     * "item_id:count" strings (null-slot == empty).
     */
    private static final Map<Long, String[]> HOPPER_SNAPSHOT = new ConcurrentHashMap<>();
    /**
     * Z3 — Parallel lookup from packed key to the BlockPos it represents.
     * We keep this so the sampler can recover the position without needing to
     * reverse the XOR-mixed encoding.
     */
    private static final Map<Long, BlockPos> HOPPER_POS_LOOKUP = new ConcurrentHashMap<>();
    /** Z3 — Rate-limit for Z3 error logging. */
    private static final Map<String, Long> Z3_WARN_LIMIT = new ConcurrentHashMap<>();

    /** Z3 — encode (worldId, pos) into a single long for map keys. */
    private static long hopperKey(String worldId, BlockPos pos) {
        long wh = worldId == null ? 0L : ((long) worldId.hashCode()) & 0xFFFFFFFFL;
        long x = ((long) pos.getX()) & 0x3FFFFFF;
        long y = ((long) pos.getY()) & 0xFFF;
        long z = ((long) pos.getZ()) & 0x3FFFFFF;
        return (wh << 32) ^ (x << 38) ^ (y << 26) ^ z;
    }

    /**
     * Z3 — Encode a single hopper slot into "id:count". Returns null for empty.
     * Diff sign encodes push (delta > 0) vs pull (delta < 0).
     */
    private static String slotKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return itemId(stack) + "|" + stack.getCount();
    }

    private static String parseSlotId(String key) {
        if (key == null) return null;
        int i = key.lastIndexOf('|');
        return i > 0 ? key.substring(0, i) : key;
    }

    private static int parseSlotCount(String key) {
        if (key == null) return 0;
        int i = key.lastIndexOf('|');
        if (i < 0 || i == key.length() - 1) return 0;
        try {
            return Integer.parseInt(key.substring(i + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void z3Register(String worldId, BlockPos pos) {
        long k = hopperKey(worldId, pos);
        java.util.LinkedHashSet<Long> set = HOPPER_POS_BY_LEVEL.computeIfAbsent(
                worldId, w -> new java.util.LinkedHashSet<>());
        // v1.3.6 CC2 (P1-1): guard under per-level lock; LinkedHashSet is
        // not thread-safe. In practice all hopper events dispatch on the
        // server thread so contention is zero, but the lock ensures we do
        // not corrupt the set if a mod off-threads a chunk-load event.
        synchronized (hopperLockFor(worldId)) {
            if (set.size() >= HOPPER_TRACKER_MAX_PER_LEVEL) return;
            if (HOPPER_SNAPSHOT.putIfAbsent(k, new String[HOPPER_SLOTS]) == null) {
                HOPPER_POS_LOOKUP.put(k, pos.immutable());
                set.add(k);
            }
        }
    }

    private static void z3Unregister(String worldId, BlockPos pos) {
        long k = hopperKey(worldId, pos);
        HOPPER_SNAPSHOT.remove(k);
        HOPPER_POS_LOOKUP.remove(k);
        // v1.3.6 CC2 (P1-1): O(1) removal via LinkedHashSet, guarded by
        // the per-level lock to serialize with z3Register and the sampler.
        java.util.LinkedHashSet<Long> set = HOPPER_POS_BY_LEVEL.get(worldId);
        if (set == null) return;
        synchronized (hopperLockFor(worldId)) {
            set.remove(k);
        }
    }

    /**
     * Z3 — Walk a chunk's block-entities and register any hoppers seen.
     * Fires once per chunk load; picks up pre-existing hoppers.
     */
    @SubscribeEvent
    public static void onChunkLoadRegisterHoppers(net.minecraftforge.event.level.ChunkEvent.Load ev) {
        try {
            net.minecraft.world.level.chunk.ChunkAccess chunk = ev.getChunk();
            if (chunk == null) return;
            net.minecraft.world.level.LevelAccessor la = ev.getLevel();
            if (!(la instanceof ServerLevel level)) return;
            String worldId = WorldKey.of(level);
            for (BlockPos p : chunk.getBlockEntitiesPos()) {
                BlockEntity be = chunk.getBlockEntity(p);
                if (be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) {
                    z3Register(worldId, p);
                }
            }
        } catch (Throwable t) {
            rateLimitedZ3Warn("onChunkLoadRegisterHoppers", t);
        }
    }

    /** Z3 — drop hoppers when their chunk unloads. */
    @SubscribeEvent
    public static void onChunkUnloadDropHoppers(net.minecraftforge.event.level.ChunkEvent.Unload ev) {
        try {
            net.minecraft.world.level.chunk.ChunkAccess chunk = ev.getChunk();
            if (chunk == null) return;
            net.minecraft.world.level.LevelAccessor la = ev.getLevel();
            if (!(la instanceof ServerLevel level)) return;
            String worldId = WorldKey.of(level);
            for (BlockPos p : chunk.getBlockEntitiesPos()) {
                BlockEntity be = chunk.getBlockEntity(p);
                if (be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) {
                    z3Unregister(worldId, p);
                }
            }
        } catch (Throwable t) {
            rateLimitedZ3Warn("onChunkUnloadDropHoppers", t);
        }
    }

    /** Z3 — Track newly-placed hoppers. */
    @SubscribeEvent
    public static void onHopperPlacedRegister(BlockEvent.EntityPlaceEvent ev) {
        try {
            BlockState placed = ev.getPlacedBlock();
            if (placed == null) return;
            if (!(placed.getBlock() instanceof net.minecraft.world.level.block.HopperBlock)) return;
            Level level = (Level) ev.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) return;
            z3Register(WorldKey.of(serverLevel), ev.getPos());
        } catch (Throwable t) {
            rateLimitedZ3Warn("onHopperPlacedRegister", t);
        }
    }

    /** Z3 — Untrack broken hoppers. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHopperBrokenUnregister(BlockEvent.BreakEvent ev) {
        try {
            BlockState state = ev.getState();
            if (state == null) return;
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.HopperBlock)) return;
            Level level = (Level) ev.getLevel();
            z3Unregister(WorldKey.of(level), ev.getPos());
        } catch (Throwable t) {
            rateLimitedZ3Warn("onHopperBrokenUnregister", t);
        }
    }

    /**
     * Z3 — Per-tick hopper content-diff sampler.
     *
     * <p>Runs at {@code TickEvent.Phase.END} for each ServerLevel; samples up
     * to {@link #HOPPER_SAMPLE_PER_TICK} hoppers via round-robin drain-and-
     * enqueue, and diffs each hopper's slot contents against the last-observed
     * snapshot. Detected slot deltas emit {@code submitHopperPush} (slot count
     * went up) or {@code submitHopperPull} (slot count went down).</p>
     */
    @SubscribeEvent
    public static void onLevelTickHopperSampler(net.minecraftforge.event.TickEvent.LevelTickEvent ev) {
        try {
            if (ev.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
            if (ev.side != net.minecraftforge.fml.LogicalSide.SERVER) return;
            Level level = ev.level;
            if (!(level instanceof ServerLevel serverLevel)) return;
            EventSubmitter s = sub();
            if (s == null) return;
            String worldId = WorldKey.of(serverLevel);
            java.util.LinkedHashSet<Long> set = HOPPER_POS_BY_LEVEL.get(worldId);
            if (set == null || set.isEmpty()) return;
            // v1.3.6 CC2 (P1-1): round-robin drain via iterator on a
            // small local snapshot. Sampling under the per-level lock keeps
            // z3Register / z3Unregister safely serialized; the snapshot is
            // bounded by HOPPER_SAMPLE_PER_TICK so we do at most 20 hoppers'
            // work per tick under lock.
            int sampled = 0;
            Object lock = hopperLockFor(worldId);
            long[] toSample = new long[HOPPER_SAMPLE_PER_TICK];
            int drained = 0;
            synchronized (lock) {
                java.util.Iterator<Long> it = set.iterator();
                while (it.hasNext() && drained < HOPPER_SAMPLE_PER_TICK) {
                    toSample[drained++] = it.next();
                    it.remove();
                }
            }
            for (int i = 0; i < drained; i++) {
                long k = toSample[i];
                sampled++;
                boolean stillTracked = sampleHopperOne(serverLevel, worldId, k, s);
                if (stillTracked) {
                    // Re-enqueue at tail so the next tick samples fresh entries.
                    synchronized (lock) {
                        set.add(k);
                    }
                }
            }
        } catch (Throwable t) {
            rateLimitedZ3Warn("onLevelTickHopperSampler", t);
        }
    }

    /**
     * Z3 — Sample one hopper: read current slot contents, diff against
     * snapshot, submit push/pull rows, update snapshot. Returns false if the
     * hopper is gone (chunk unloaded, block replaced).
     */
    private static boolean sampleHopperOne(ServerLevel level, String worldId, long k, EventSubmitter s) {
        BlockPos pos = HOPPER_POS_LOOKUP.get(k);
        if (pos == null) {
            HOPPER_SNAPSHOT.remove(k);
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper)) {
            HOPPER_SNAPSHOT.remove(k);
            HOPPER_POS_LOOKUP.remove(k);
            return false;
        }
        String[] snap = HOPPER_SNAPSHOT.get(k);
        if (snap == null) snap = new String[HOPPER_SLOTS];
        boolean firstObservation = allNull(snap);
        for (int i = 0; i < HOPPER_SLOTS; i++) {
            ItemStack cur = i < hopper.getContainerSize() ? hopper.getItem(i) : ItemStack.EMPTY;
            String curKey = slotKey(cur);
            String prevKey = snap[i];
            snap[i] = curKey;
            if (firstObservation) continue;
            if (java.util.Objects.equals(prevKey, curKey)) continue;
            int prevCount = parseSlotCount(prevKey);
            int curCount = parseSlotCount(curKey);
            String itemIdCur = parseSlotId(curKey);
            String itemIdPrev = parseSlotId(prevKey);
            // Item type CHANGED entirely: emit pull(prev) + push(cur) so
            // rollback can undo both sides of the swap. This can happen if the
            // hopper drained an item and got a different one within the same
            // sample window; a rare corner case, but under-log is worse than
            // false log.
            if (prevKey != null && curKey != null && itemIdCur != null
                    && itemIdPrev != null && !itemIdCur.equals(itemIdPrev)) {
                s.submitHopperPull(null, Sentinel.HOPPER, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), itemIdPrev, prevCount, Sentinel.HOPPER);
                s.submitHopperPush(null, Sentinel.HOPPER, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), itemIdCur, curCount, Sentinel.HOPPER);
                continue;
            }
            String itemId = curKey != null ? itemIdCur : itemIdPrev;
            if (itemId == null) continue;
            int delta = curCount - prevCount;
            if (delta > 0) {
                s.submitHopperPush(null, Sentinel.HOPPER, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), itemId, delta, Sentinel.HOPPER);
            } else if (delta < 0) {
                s.submitHopperPull(null, Sentinel.HOPPER, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), itemId, -delta, Sentinel.HOPPER);
            }
        }
        HOPPER_SNAPSHOT.put(k, snap);
        return true;
    }

    private static boolean allNull(String[] a) {
        if (a == null) return true;
        for (String s : a) if (s != null) return false;
        return true;
    }

    /**
     * Z3 — CommandEvent hook: detect /fill and /setblock, snapshot region,
     * defer per-block diff to next tick on the server executor.
     */
    @SubscribeEvent
    public static void onCommandFillSetblock(CommandEvent ev) {
        try {
            ParseResults<CommandSourceStack> res = ev.getParseResults();
            if (res == null) return;
            // v1.3.6 CC2 (P1-5): server-side gate. CommandEvent fires on both
            // logical sides on integrated servers; without this guard we do
            // the entire /fill snapshot work twice per fill on singleplayer
            // AND we may see a null level on the client-side dispatch. The
            // src.getLevel() check below is redundant belt-and-braces.
            CommandSourceStack srcGate = res.getContext().getSource();
            if (srcGate == null || srcGate.getLevel() == null) return;
            String raw = res.getReader().getString();
            if (raw == null) return;
            String trimmed = raw.startsWith("/") ? raw.substring(1) : raw;
            String cmdName = trimmed.split("\\s+", 2)[0];
            boolean isFill = cmdName.equals("fill") || cmdName.endsWith(":fill");
            boolean isSetblock = cmdName.equals("setblock") || cmdName.endsWith(":setblock");
            if (!isFill && !isSetblock) return;

            CommandSourceStack src = res.getContext().getSource();
            if (src == null) return;
            ServerLevel level = src.getLevel();
            if (level == null) return;
            String worldId = WorldKey.of(level);
            String sourceTag = isFill ? "cmd:fill" : "cmd:setblock";

            UUID actorUuid = null;
            String actorName = Sentinel.COMMAND;
            if (src.getEntity() instanceof Player pl) {
                actorUuid = pl.getUUID();
                actorName = pl.getName().getString();
            }

            // Extract BlockPos + optional BlockInput from the parsed args.
            java.util.List<BlockPos> corners = new java.util.ArrayList<>(2);
            try {
                var argsMap = res.getContext().getArguments();
                for (var entry : argsMap.entrySet()) {
                    Object result;
                    try {
                        result = entry.getValue().getResult();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (result instanceof net.minecraft.commands.arguments.coordinates.Coordinates coords) {
                        try {
                            corners.add(coords.getBlockPos(src));
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            if (corners.isEmpty()) return; // acknowledged gap: no coords -> no diff
            BlockPos min, max;
            if (corners.size() >= 2) {
                BlockPos a = corners.get(0), b = corners.get(1);
                min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
                max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            } else {
                min = corners.get(0);
                max = min;
            }
            long volume = (long)(max.getX() - min.getX() + 1)
                        * (long)(max.getY() - min.getY() + 1)
                        * (long)(max.getZ() - min.getZ() + 1);
            if (volume <= 0 || volume > FILL_MAX_REGION_BLOCKS) return;

            String[] preState = new String[(int) volume];
            int idx = 0;
            // v1.3.6 CC2 (P1-3/P1-4): reuse a MutableBlockPos across the
            // pre-snapshot scan. Pre-1.3.6 allocated one BlockPos per cell —
            // up to FILL_MAX_REGION_BLOCKS = 32,768 allocations per /fill
            // that we then throw away after level.getBlockState. Reusing a
            // MutableBlockPos removes the allocation entirely.
            BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    for (int x = min.getX(); x <= max.getX(); x++) {
                        BlockState st = level.getBlockState(scratch.set(x, y, z));
                        preState[idx++] = st != null ? blockId(st) : "minecraft:air";
                    }
                }
            }
            final BlockPos fmin = min;
            final BlockPos fmax = max;
            final String[] preSnap = preState;
            final UUID fActorUuid = actorUuid;
            final String fActorName = actorName;
            final String fSourceTag = sourceTag;
            final String fWorldId = worldId;

            level.getServer().execute(() -> {
                try {
                    EventSubmitter s2 = sub();
                    if (s2 == null) return;
                    int i = 0;
                    // v1.3.6 CC2 (P1-3/P1-4): same MutableBlockPos reuse for
                    // the deferred post-state diff pass. Runs on next-tick
                    // server executor; without this the callback allocated
                    // another 32K BlockPos per /fill.
                    BlockPos.MutableBlockPos scratch2 = new BlockPos.MutableBlockPos();
                    for (int y = fmin.getY(); y <= fmax.getY(); y++) {
                        for (int z = fmin.getZ(); z <= fmax.getZ(); z++) {
                            for (int x = fmin.getX(); x <= fmax.getX(); x++) {
                                BlockState nowSt = level.getBlockState(scratch2.set(x, y, z));
                                String nowId = nowSt != null ? blockId(nowSt) : "minecraft:air";
                                String prevId = preSnap[i++];
                                if (prevId == null || prevId.equals(nowId)) continue;
                                if (!"minecraft:air".equals(prevId)) {
                                    s2.submitBlockBreak(fActorUuid, fActorName, fWorldId,
                                            x, y, z, prevId, fSourceTag);
                                }
                                if (!"minecraft:air".equals(nowId)) {
                                    s2.submitBlockPlace(fActorUuid, fActorName, fWorldId,
                                            x, y, z, nowId, fSourceTag);
                                }
                            }
                        }
                    }
                } catch (Throwable t2) {
                    rateLimitedZ3Warn("onCommandFillSetblock.deferred", t2);
                }
            });
        } catch (Throwable t) {
            rateLimitedZ3Warn("onCommandFillSetblock", t);
        }
    }

    /** Z3 — clear tracker state on server stop; called via {@link #reset()}. */
    public static void clearHopperTracker() {
        HOPPER_POS_BY_LEVEL.clear();
        HOPPER_LOCK_BY_LEVEL.clear();
        HOPPER_SNAPSHOT.clear();
        HOPPER_POS_LOOKUP.clear();
        Z3_WARN_LIMIT.clear();
    }

    private static void rateLimitedZ3Warn(String site, Throwable t) {
        long now = System.currentTimeMillis();
        String key = site + ":" + t.getClass().getName();
        Long last = Z3_WARN_LIMIT.get(key);
        if (last == null || now - last >= 60_000L) {
            Z3_WARN_LIMIT.put(key, now);
            LOG.warn(Guardian.MARKER, "{} failed", site, t);
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
