/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
    private static final Map<String, Long> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    /** Daemon worker for off-tick JDBC (inspector lookups). */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VonixGuardian-NeoForgeEvents-Worker");
        t.setDaemon(true);
        return t;
    });

    /** Last right-clicked container position per player (for open/close snapshot diff). */
    private static final Map<UUID, BlockPos> LAST_CONTAINER_RC = new ConcurrentHashMap<>();
    /** Snapshot of container contents at open time, keyed by player UUID. */
    private static final Map<UUID, Map<Integer, ItemStack>> CONTAINER_SNAPSHOT = new ConcurrentHashMap<>();

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
            s.submitBlockBreak(p.getUUID(), p.getName().getString(),
                    WorldKey.of((Level) ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId, null);
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
                    oldId, "minecraft:air", SourceTagger.tag(e));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDestroyBlock failed", t);
        }
    }

    private static String stripMobPrefix(String sentinel) {
        if (sentinel == null || !sentinel.startsWith("#mob:")) return null;
        return sentinel.substring(5);
    }

    /** Explosion detonate — log every affected block with a joined target list. */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate ev) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            var affected = ev.getAffectedBlocks();
            if (affected == null || affected.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            int cap = 4096;
            int count = 0;
            for (BlockPos p : affected) {
                if (sb.length() > cap) break;
                if (count++ > 0) sb.append(',');
                sb.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ());
            }
            Entity source = ev.getExplosion().getDirectSourceEntity();
            Attribution attr = (source != null && NeoForgeBootstrap.resolver != null)
                    ? NeoForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
            BlockPos center = BlockPos.containing(ev.getExplosion().center());
            s.submitExplosion(attr.actorUuid(),
                    attr.actorName() != null ? attr.actorName() : Sentinel.EXPLOSION,
                    WorldKey.of((Level) ev.getLevel()),
                    center.getX(), center.getY(), center.getZ(),
                    sb.toString(),
                    source != null ? SourceTagger.tag(source) : Sentinel.EXPLOSION);
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
            s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(victim.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    entityType, SourceTagger.tag(ev.getSource()));
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
            Long last = SPAWN_LIMIT.get(type);
            if (last != null && now - last < SPAWN_LIMIT_MS) return;
            SPAWN_LIMIT.put(type, now);
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
            s.submitItemDrop(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
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

    /** Right-click block -> CLICK if {@code logInteractions}; also snapshot container pos for delta tracking. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock ev) {
        try {
            Player p = ev.getEntity();
            if (p == null) return;
            // Container snapshot tracking (independent of logInteractions config).
            try {
                if (p instanceof ServerPlayer sp) {
                    BlockEntity be = ev.getLevel().getBlockEntity(ev.getPos());
                    if (be instanceof Container) {
                        LAST_CONTAINER_RC.put(sp.getUUID(), ev.getPos().immutable());
                    }
                }
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "onRightClickBlock (container snapshot) failed", t);
            }
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            if (!c.actions().logInteractions()) return;
            BlockPos pos = ev.getPos();
            BlockState state = ev.getLevel().getBlockState(pos);
            s.submitClick(p.getUUID(), p.getName().getString(),
                    WorldKey.of(ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(state), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onRightClickBlock failed", t);
        }
    }

    /** Container opened -> snapshot contents for diff at close. */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open ev) {
        try {
            if (!(ev.getEntity() instanceof ServerPlayer sp)) return;
            BlockPos pos = LAST_CONTAINER_RC.get(sp.getUUID());
            if (pos == null) return;
            BlockEntity be = sp.level().getBlockEntity(pos);
            if (!(be instanceof Container c)) return;
            Map<Integer, ItemStack> snap = new HashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) snap.put(i, c.getItem(i).copy());
            CONTAINER_SNAPSHOT.put(sp.getUUID(), snap);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onContainerOpen failed", t);
        }
    }

    /** Container closed -> compute delta vs open-time snapshot, submit deposit/withdraw rows. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close ev) {
        try {
            if (!(ev.getEntity() instanceof ServerPlayer sp)) return;
            Map<Integer, ItemStack> snap = CONTAINER_SNAPSHOT.remove(sp.getUUID());
            BlockPos pos = LAST_CONTAINER_RC.remove(sp.getUUID());
            if (snap == null || pos == null) return;
            BlockEntity be = sp.level().getBlockEntity(pos);
            if (!(be instanceof Container c)) return;
            String worldId = WorldKey.of(sp.level());
            EventSubmitter s = sub();
            if (s == null) return;
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack before = snap.getOrDefault(slot, ItemStack.EMPTY);
                ItemStack after = c.getItem(slot);
                int beforeCount = before.isEmpty() ? 0 : before.getCount();
                int afterCount = after.isEmpty() ? 0 : after.getCount();
                String itemId = !before.isEmpty() ? itemId(before) : (!after.isEmpty() ? itemId(after) : null);
                if (itemId == null) continue;
                int delta = afterCount - beforeCount;
                if (delta == 0) continue;
                s.submitContainerChange(sp.getUUID(), sp.getName().getString(), worldId,
                        pos.getX(), pos.getY(), pos.getZ(), itemId, delta, null);
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

    // TODO(1.0.5): NeoForge 21.1 removed the public FillBucketEvent that 1.20.1 Forge
    // exposes — the bucket fill/empty path is now handled internally by BucketItem /
    // CommonHooks#onFillBucket without a fire-able event. CoreProtect-parity bucket
    // logging here requires either (a) a Mixin into BucketItem#use, or (b) listening
    // to BlockEvent.FluidPlaceBlockEvent + a UseItemOnBlockEvent pre-check. Deferred.

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
     *  across an in-JVM restart. Idempotent. */
    public static void reset() {
        pendingDispatcher = null;
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
