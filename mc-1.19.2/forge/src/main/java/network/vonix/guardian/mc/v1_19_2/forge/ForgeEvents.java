/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.IpHasher;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v1_19_2.common.ChatRenderer;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_19_2.common.GuardianCommands;
import network.vonix.guardian.mc.v1_19_2.common.Inspector;
import network.vonix.guardian.mc.v1_19_2.common.SourceTagger;
import network.vonix.guardian.mc.v1_19_2.common.WorldKey;
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
 * Static {@code @SubscribeEvent} handlers for Forge 1.19.2.
 *
 * <p>Differences from 1.20.1 Forge:
 * <ul>
 *   <li>{@code BuiltInRegistries} doesn't exist — use {@link Registry#BLOCK}
 *       and {@link Registry#ITEM}.</li>
 *   <li>Everything else (event classes, packages) is identical to 1.20.1
 *       (Forge moved to {@code event.level.*} when Mojang renamed World → Level
 *       in 1.19).</li>
 * </ul>
 */
public final class ForgeEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeEvents.class);

    private static final Map<String, Long> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    /** Daemon worker for off-tick JDBC (inspector lookups). */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VonixGuardian-ForgeEvents-Worker");
        t.setDaemon(true);
        return t;
    });

    /** Last right-clicked container position per player. */
    private static final Map<UUID, BlockPos> LAST_CONTAINER_RC = new ConcurrentHashMap<>();
    /** Snapshot of container contents at open time, keyed by player UUID. */
    private static final Map<UUID, Map<Integer, ItemStack>> CONTAINER_SNAPSHOT = new ConcurrentHashMap<>();

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
                        final MinecraftServer server = sp.getServer();
                        final String wid = WorldKey.of((Level) ev.getLevel());
                        final int x = pos.getX(), y = pos.getY(), z = pos.getZ();
                        WORKER.submit(() -> {
                            try {
                                List<String> lines = gg.lookupAtPos(wid, x, y, z, 10, System.currentTimeMillis());
                                if (server != null) {
                                    server.execute(() -> {
                                        for (String line : lines) {
                                            sp.sendSystemMessage(ChatRenderer.primary(gg.theme(), line));
                                        }
                                    });
                                }
                            } catch (Throwable t) {
                                LOG.warn(Guardian.MARKER, "inspector lookup failed at {} {},{},{}", wid, x, y, z, t);
                                if (server != null) {
                                    server.execute(() -> sp.sendSystemMessage(ChatRenderer.error(gg.theme(),
                                            "[VonixGuardian] Inspect lookup error: " + t.getMessage())));
                                }
                            }
                        });
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
            if (s == null) return;
            LivingEntity e = ev.getEntity();
            if (e == null) return;
            Attribution attr = ForgeBootstrap.resolver != null
                    ? ForgeBootstrap.resolver.resolve(e, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.of(e));
            BlockPos pos = ev.getPos();
            String oldId = blockId(ev.getState());
            s.submitEntityChangeBlock(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(e.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    oldId, "minecraft:air", SourceTagger.tag(e));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onLivingDestroyBlock failed", t);
        }
    }

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
            Entity source = ev.getExplosion().getExploder();
            Attribution attr = (source != null && ForgeBootstrap.resolver != null)
                    ? ForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
            Vec3 c = ev.getExplosion().getPosition();
            BlockPos center = new BlockPos((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z));
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

    /** 1.19.2 has the single-class {@code LivingDamageEvent} (no .Pre/.Post). */
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
            s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(victim.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    entityType, SourceTagger.tag(ev.getSource()));
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
            Long last = SPAWN_LIMIT.get(type);
            if (last != null && now - last < SPAWN_LIMIT_MS) return;
            SPAWN_LIMIT.put(type, now);
            BlockPos pos = e.blockPosition();
            s.submitEntitySpawn(null, type,
                    WorldKey.of(ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(), type, "spawn:join");
        } catch (Throwable t) {
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
                addr = sp.connection.connection.getRemoteAddress() != null
                        ? sp.connection.connection.getRemoteAddress().toString()
                        : "";
            } catch (Throwable ignored) {
                addr = "";
            }
            String ipField = c.privacy().hashIps()
                    ? IpHasher.hash(addr, c.privacy().salt())
                    : addr;
            s.submitSessionJoin(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level), ipField);
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
                    WorldKey.of(p.level), "logout");
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
                    WorldKey.of(p.level), msg);
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
            String raw = res.getReader().getString();
            if (src.getEntity() instanceof ServerPlayer p) {
                s.submitCommand(p.getUUID(), p.getName().getString(),
                        WorldKey.of(p.level), "/" + raw);
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
            s.submitItemDrop(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onItemToss failed", t);
        }
    }

    /** 1.19.2 Forge uses {@code PlayerEvent.ItemPickupEvent}. */
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
                    WorldKey.of(p.level),
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
                    WorldKey.of(p.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onItemCrafted failed", t);
        }
    }

    // ====================================================================== interaction

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
            BlockEntity be = sp.level.getBlockEntity(pos);
            if (!(be instanceof Container c)) return;
            Map<Integer, ItemStack> snap = new HashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) snap.put(i, c.getItem(i).copy());
            CONTAINER_SNAPSHOT.put(sp.getUUID(), snap);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onContainerOpen failed", t);
        }
    }

    /** Container closed -> diff vs snapshot, submit deposit/withdraw rows. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close ev) {
        try {
            if (!(ev.getEntity() instanceof ServerPlayer sp)) return;
            Map<Integer, ItemStack> snap = CONTAINER_SNAPSHOT.remove(sp.getUUID());
            BlockPos pos = LAST_CONTAINER_RC.remove(sp.getUUID());
            if (snap == null || pos == null) return;
            BlockEntity be = sp.level.getBlockEntity(pos);
            if (!(be instanceof Container c)) return;
            String worldId = WorldKey.of(sp.level);
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

    // ============ buckets

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
                    ? Registry.ITEM.getKey(heldItem).toString()
                    : "minecraft:bucket";
            String worldId = WorldKey.of(p.level);
            // Heuristic: stack passed via getEmptyBucket() is what the player CURRENTLY
            // holds. If it's a plain bucket they're filling; otherwise they're emptying.
            if (heldItem != null && "minecraft:bucket".equals(heldId)) {
                // FILL — block becomes air, bucket gains content
                BlockState state = p.level.getBlockState(pos);
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
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onFillBucket failed", t);
        }
    }

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
    }

    // ====================================================================== helpers

    private static String blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    private static String itemId(ItemStack stack) {
        try {
            ResourceLocation rl = Registry.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
