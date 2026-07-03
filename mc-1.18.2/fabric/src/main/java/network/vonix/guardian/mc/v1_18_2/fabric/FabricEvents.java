/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric;

import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.IpHasher;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v1_18_2.common.ChatRenderer;
import network.vonix.guardian.mc.v1_18_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_18_2.common.GuardianCommands;
import network.vonix.guardian.mc.v1_18_2.common.Inspector;
import network.vonix.guardian.mc.v1_18_2.common.SourceTagger;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fabric event handlers for 1.18.2.
 *
 * <p><b>v1.2.0 parity note (W4-03):</b> This cell wires everything the Fabric API
 * exposes natively on 1.18.2. Beyond the 1.19+ ports, 1.18.2 additionally lacks
 * {@code ServerLivingEntityEvents.ALLOW_DAMAGE} / {@code AFTER_DEATH} and any
 * {@code ServerMessageEvents} — chat, command capture, natural-death logging,
 * and damage-history population all require mixins. See W4-04.
 *
 * <p>1.18.2 source quirks vs 1.20.1:
 * <ul>
 *   <li>{@code BuiltInRegistries} -> {@link Registry#BLOCK}.</li>
 *   <li>{@code Entity.level} is a public field, not a {@code level()} method.</li>
 *   <li>{@code Player#sendMessage(Component, UUID)} (no {@code sendSystemMessage}
 *       yet — that arrived in 1.19.x).</li>
 * </ul>
 */
public final class FabricEvents {

    private static final Logger LOG = LoggerFactory.getLogger(FabricEvents.class);

    // v1.3.0 W2: de-boxed spawn-limit throttle. Previous Map<String,Long> auto-boxed a
    // fresh Long on every put(...) — several MB/s of short-lived garbage on modded
    // shards with mob-farm-heavy chunks (piglin bartering, drowned trident farms, etc.).
    // AtomicLong lets us update in place with a plain volatile store on the hot path.
    private static final Map<String, AtomicLong> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    /** Daemon worker for off-tick JDBC (inspector lookups). Mirrors ForgeEvents. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VonixGuardian-FabricEvents-Worker");
        t.setDaemon(true);
        return t;
    });

    private FabricEvents() {
        // utility
    }

    public static void register() {
        // ---- blocks
        PlayerBlockBreakEvents.AFTER.register(FabricEvents::onBlockBreakAfter);
        UseBlockCallback.EVENT.register(FabricEvents::onUseBlock);
        AttackBlockCallback.EVENT.register(FabricEvents::onAttackBlock);

        // ---- combat (kills only — no ALLOW_DAMAGE / AFTER_DEATH on 1.18.2 fabric-api)
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(FabricEvents::onAfterKilledOther);

        // ---- entities (mob spawn + hanging place share the same callback)
        ServerEntityEvents.ENTITY_LOAD.register(FabricEvents::onEntityLoad);

        // ---- interaction (hanging break — player-caused)
        AttackEntityCallback.EVENT.register(FabricEvents::onAttackEntity);
        UseEntityCallback.EVENT.register(FabricEvents::onUseEntity);

        // ---- sessions
        ServerPlayConnectionEvents.JOIN.register(FabricEvents::onJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(FabricEvents::onDisconnect);

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            try {
                Guardian g = VonixGuardianFabric.guardian();
                if (g == null) {
                    pendingDispatcher = dispatcher;
                    LOG.info(Guardian.MARKER, "Deferred /vg command registration until Guardian.boot");
                    return;
                }
                GuardianCommands.register(dispatcher, g);
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "CommandRegistration failed", t);
            }
        });

        // ================================================================== Fabric-API gaps
        //
        // W5-08 UPDATE: The following event categories are now covered by
        // Fabric mixins (see vg.mixins.json + `mixin/` package):
        //   - BLOCK_PLACE       (BlockPlaceMixin on BlockItem#place)
        //   - ENTITY_CHANGE     (LivingDestroyBlockMixin on Level#destroyBlock)
        //   - EXPLOSION         (ExplosionMixin on Explosion#finalizeExplosion)
        //   - PISTON_EXTEND/RETRACT (PistonMixin on PistonBaseBlock#moveBlocks)
        //   - CONTAINER open/close  (ContainerMixin on ChestBlockEntity)
        //   - BUCKET_FILL/EMPTY (BucketItemMixin on BucketItem#use)
        //   - ITEM_DROP         (ItemTossMixin on Player#drop)
        //   - ITEM_PICKUP       (ItemPickupMixin on ItemEntity#playerTouch)
        //   - ITEM_CRAFT        (CraftItemMixin on ResultSlot#onTake)
        //   - SIGN_CHANGE       (SignChangeMixin on ServerGamePacketListenerImpl#handleSignUpdate)
        //
        // Still deferred (v1.2.1):
        //   - FIRE_BURN, FIRE_IGNITE, ICE_FADE, DISPENSE, LEAVES_DECAY,
        //     BLOCK_FORM/SPREAD  (vanilla block tick mixins — W2-P2 wave)
        //   - Barrel / shulker / hopper container snapshots (ContainerMixin
        //     currently covers ChestBlockEntity only)
        //   - Non-player hanging break (arrow/explosion/mob)
        //
        // (original wave-note block, kept for history) ================================================================== Fabric-API gaps
        // HISTORY(v1.2.0): mixin — W4-04/W4-06 (delivered W5-08)
        //   Fabric API 0.77.0+1.18.2 has NO native event for the following. Each
        //   requires a dedicated mixin.
        //
        //   - CHAT              → mixin on ServerGamePacketListenerImpl#handleChat
        //                          (1.19+ has ServerMessageEvents.CHAT_MESSAGE; 1.18.2 does not).
        //   - COMMAND           → mixin on Commands#performCommand or
        //                          ServerGamePacketListenerImpl#handleCommand
        //                          (1.19+ has ServerMessageEvents.COMMAND_MESSAGE; 1.18.2 does not).
        //   - DAMAGE_HISTORY    → mixin on LivingEntity#actuallyHurt
        //                          (1.19+ has ServerLivingEntityEvents.ALLOW_DAMAGE; 1.18.2 does not).
        //   - AFTER_DEATH       → mixin on LivingEntity#die for natural deaths
        //                          (fall / drown / lava / suffocation — killer==null path;
        //                          1.18.2 fabric-api's AFTER_KILLED_OTHER_ENTITY skips those).
        //   - BLOCK_PLACE       → mixin on BlockItem#place / Level#setBlock.
        //   - ENTITY_CHANGE     → mixin on universal mob-griefing paths.
        //   - EXPLOSION         → mixin on Explosion#finalizeExplosion.
        //   - PISTON_EXTEND/RETRACT → mixin on PistonBaseBlock#moveBlocks.
        //   - CONTAINER_OPEN    → mixin on ServerPlayer#openMenu snapshot.
        //   - CONTAINER_CLOSE   → mixin on ServerPlayer#doCloseContainer diff.
        //   - BUCKET_FILL/EMPTY → mixin on BucketItem#use / #emptyContents.
        //   - ITEM_DROP         → mixin on Player#drop.
        //   - ITEM_PICKUP       → mixin on ItemEntity#playerTouch.
        //   - ITEM_CRAFT        → mixin on ResultSlot#onTake.
        //   - SIGN_CHANGE       → mixin on SignBlockEntity or the update-sign packet handler.
        //   - FIRE_BURN         → mixin on FireBlock#tick / #checkBurnOut.
        //   - FIRE_IGNITE       → mixin on FireBlock#tick + FlintAndSteelItem#useOn.
        //   - ICE_FADE          → mixin on IceBlock#tick / SnowLayerBlock#tick.
        //   - DISPENSE          → mixin on DispenserBlockEntity#dispenseFrom.
        //   - LEAVES_DECAY      → mixin on LeavesBlock#randomTick.
        //   - BLOCK_FORM/SPREAD → mixin on GrassBlock/MyceliumBlock/VineBlock tick,
        //                          ConcretePowderBlock, WaterBlock/LavaBlock freeze/harden.
        //   All wired in W4-04 (block state changes) and W4-06 (container + item ops).
    }

    // ====================================================================== access

    private static Guardian g() {
        return VonixGuardianFabric.guardian();
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

    private static void onBlockBreakAfter(Level world, Player player, BlockPos pos,
                                          BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null) return;
            // v1.3.2 Y1: NBT capture on server thread BEFORE the break resolves.
            if (persistNbt()) {
                String stateProps = NbtCapture.blockStateProps(state);
                byte[] beNbt = be == null ? null : NbtCapture.blockEntity(be);
                s.submitBlockBreak(player.getUUID(), player.getName().getString(),
                        WorldKey.of(world),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId(state), null, stateProps, beNbt);
            } else {
                s.submitBlockBreak(player.getUUID(), player.getName().getString(),
                        WorldKey.of(world),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId(state), null);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onBlockBreakAfter failed", t);
        }
    }

    private static InteractionResult onUseBlock(Player player,
                                                Level world,
                                                net.minecraft.world.InteractionHand hand,
                                                net.minecraft.world.phys.BlockHitResult hit) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null || player == null) return InteractionResult.PASS;
            if (!c.actions().logInteractions()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            s.submitClick(player.getUUID(), player.getName().getString(),
                    WorldKey.of(world),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(state), null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onUseBlock failed", t);
        }
        return InteractionResult.PASS;
    }

    /**
     * Left-click block. Two responsibilities:
     * <ol>
     *   <li>If the player is inspecting, swallow the swing and run an async
     *       lookup at the clicked position, mirroring
     *       {@code ForgeEvents#onLeftClickBlock}.</li>
     *   <li>Otherwise pass through so vanilla break processing runs normally.</li>
     * </ol>
     */
    private static InteractionResult onAttackBlock(Player player,
                                                   Level world,
                                                   net.minecraft.world.InteractionHand hand,
                                                   BlockPos pos,
                                                   net.minecraft.core.Direction direction) {
        try {
            if (player == null) return InteractionResult.PASS;
            if (!Inspector.isInspecting(player.getUUID())) return InteractionResult.PASS;
            Guardian gg = g();
            if (gg == null) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundBlockUpdatePacket(pos, world.getBlockState(pos)));
                final MinecraftServer server = sp.getServer();
                final String worldId = WorldKey.of(world);
                final int x = pos.getX(), y = pos.getY(), z = pos.getZ();
                WORKER.submit(() -> {
                    try {
                        List<String> lines = gg.lookupAtPos(worldId, x, y, z, 10, System.currentTimeMillis());
                        if (server != null) {
                            server.execute(() -> {
                                for (String line : lines) {
                                    // 1.18.2: sendMessage(Component, UUID) — no sendSystemMessage.
                                    sp.sendMessage(ChatRenderer.primary(gg.theme(), line), Util.NIL_UUID);
                                }
                            });
                        }
                    } catch (Throwable t) {
                        LOG.warn(Guardian.MARKER, "inspector lookup failed at {} {},{},{}", worldId, x, y, z, t);
                        if (server != null) {
                            server.execute(() -> sp.sendMessage(ChatRenderer.error(gg.theme(),
                                    "[VonixGuardian] Inspect lookup error: " + t.getMessage()), Util.NIL_UUID));
                        }
                    }
                });
            }
            return InteractionResult.SUCCESS;
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAttackBlock failed", t);
            return InteractionResult.PASS;
        }
    }

    // ====================================================================== combat

    /**
     * Fires only when the killer is a non-null {@link Entity} (covers PvP, mob
     * kills, and projectile kills). Natural deaths (fall, drown, lava, etc.) are
     * NOT covered here — they need a Mixin (see W4-04 HISTORY block above).
     */
    private static void onAfterKilledOther(net.minecraft.server.level.ServerLevel level,
                                           Entity killer,
                                           LivingEntity victim) {
        try {
            EventSubmitter s = sub();
            if (s == null || victim == null || killer == null) return;
            Attribution attr;
            if (FabricBootstrap.resolver != null) {
                attr = FabricBootstrap.resolver.resolve(killer, System.currentTimeMillis());
            } else {
                attr = Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            BlockPos pos = victim.blockPosition();
            String entityType = EntitySentinel.of(victim);
            s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    entityType, SourceTagger.tag(killer));
            if (FabricBootstrap.damageHistory != null) {
                FabricBootstrap.damageHistory.forget(victim.getUUID());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAfterKilledOther failed", t);
        }
    }

    // ====================================================================== entities

    /**
     * ENTITY_LOAD is Fabric's single hook for "new entity into world" — it covers
     * both mob spawning and hanging-entity placement. We fan out here:
     * <ul>
     *   <li>{@link HangingEntity} → HANGING_PLACE via universal attribution.</li>
     *   <li>Other {@link LivingEntity} (non-player) → ENTITY_SPAWN, rate-limited.</li>
     * </ul>
     *
     * <p><b>Known gap vs Forge:</b> Fabric's ENTITY_LOAD does not expose a
     * {@code loadedFromDisk()} discriminator, so chunk-load reanimations cannot
     * be filtered without a mixin. The per-type 1s rate limit blunts the flood
     * but does not eliminate it; the full MobSpawnType classifier is scheduled
     * for W4-04.
     */
    private static void onEntityLoad(Entity entity, net.minecraft.server.level.ServerLevel level) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null || entity == null) return;

            // ---- hanging place (item frames, paintings, leash knots)
            if (entity instanceof HangingEntity) {
                onHangingLoad(entity, level);
                return;
            }

            if (!c.actions().logEntities()) return;
            if (!(entity instanceof LivingEntity) || entity instanceof Player) return;
            String type = EntitySentinel.of(entity);
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
            BlockPos pos = entity.blockPosition();
            s.submitEntitySpawn(null, type,
                    WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), type, null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onEntityLoad failed", t);
        }
    }

    /**
     * W2-02 / A10 parity — HANGING_PLACE. Attribution runs through the shared
     * damage-history resolver so a player-placed frame gets the placer as actor;
     * synthetic sources fall back to {@link Sentinel#UNKNOWN}.
     *
     * <p>HISTORY(v1.2.0): mixin — W4-04/W4-06 (delivered W5-08) — without ENTITY_LOAD exposing a
     * loaded-from-disk flag we cannot suppress chunk-reload replays.
     */
    private static void onHangingLoad(Entity e, net.minecraft.server.level.ServerLevel level) {
        try {
            EventSubmitter s = sub();
            if (s == null) return;
            String type = EntitySentinel.of(e);
            BlockPos pos = e.blockPosition();
            Attribution attr = FabricBootstrap.resolver != null
                    ? FabricBootstrap.resolver.resolve(e, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
            s.submitHangingPlace(attr.actorUuid(),
                    attr.actorName() != null ? attr.actorName() : Sentinel.UNKNOWN,
                    WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    type, SourceTagger.tag(e));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onHangingLoad failed", t);
        }
    }

    // ====================================================================== interaction (hanging break)

    /**
     * W2-02 / A10 parity — HANGING_BREAK, player-caused only.
     * {@link AttackEntityCallback} fires the tick a player left-clicks an entity;
     * if the target is a {@link HangingEntity} vanilla removes it on that swing.
     *
     * <p>HISTORY(v1.2.0): mixin — W4-04/W4-06 (delivered W5-08) — arrow / explosion / mob-caused
     * hanging breaks need a mixin on {@code HangingEntity#kill()}.
     */
    private static InteractionResult onAttackEntity(Player player,
                                                    Level world,
                                                    net.minecraft.world.InteractionHand hand,
                                                    Entity target,
                                                    net.minecraft.world.phys.EntityHitResult hit) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || target == null) return InteractionResult.PASS;
            if (!(target instanceof HangingEntity)) return InteractionResult.PASS;
            String type = EntitySentinel.of(target);
            BlockPos pos = target.blockPosition();
            s.submitHangingBreak(player.getUUID(), player.getName().getString(),
                    WorldKey.of(world),
                    pos.getX(), pos.getY(), pos.getZ(),
                    type, SourceTagger.tag(target));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAttackEntity failed", t);
        }
        return InteractionResult.PASS; // observe only — do not cancel damage
    }

    /** Generic right-click entity interactions: audit-only ENTITY_INTERACT, gated by logInteractions. */
    private static InteractionResult onUseEntity(Player player,
                                                Level world,
                                                net.minecraft.world.InteractionHand hand,
                                                Entity target,
                                                net.minecraft.world.phys.EntityHitResult hit) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null || player == null || world == null || target == null) {
                return InteractionResult.PASS;
            }
            if (!c.actions().logInteractions()) return InteractionResult.PASS;
            String type = EntitySentinel.of(target);
            BlockPos pos = target.blockPosition();
            s.submitEntityInteract(player.getUUID(), player.getName().getString(),
                    WorldKey.of(world),
                    pos.getX(), pos.getY(), pos.getZ(),
                    type, SourceTagger.tag(target));
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onUseEntity failed", t);
        }
        return InteractionResult.PASS;
    }

    // ====================================================================== sessions

    private static void onJoin(net.minecraft.server.network.ServerGamePacketListenerImpl handler,
                               net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                               net.minecraft.server.MinecraftServer server) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null || handler == null) return;
            ServerPlayer p = handler.player;
            if (p == null) return;
            String addr;
            try {
                // 1.18.2: ServerGamePacketListenerImpl has no getRemoteAddress();
                // go through the underlying Connection.
                java.net.SocketAddress sa = handler.connection != null
                        ? handler.connection.getRemoteAddress()
                        : null;
                addr = sa != null ? sa.toString() : "";
            } catch (Throwable ignored) {
                addr = "";
            }
            String ipField = c.privacy().hashIps()
                    ? IpHasher.hash(addr, c.privacy().salt())
                    : addr;
            s.submitSessionJoin(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level), ipField);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onJoin failed", t);
        }
    }

    private static void onDisconnect(net.minecraft.server.network.ServerGamePacketListenerImpl handler,
                                     net.minecraft.server.MinecraftServer server) {
        try {
            EventSubmitter s = sub();
            if (s == null || handler == null) return;
            ServerPlayer p = handler.player;
            if (p == null) return;
            s.submitSessionLeave(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level), "logout");
            Inspector.forget(p.getUUID());
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onDisconnect failed", t);
        }
    }

    // ====================================================================== commands replay

    private static volatile com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> pendingDispatcher;

    public static void replayDeferredCommands(Guardian g) {
        com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d = pendingDispatcher;
        if (d != null) {
            try {
                GuardianCommands.register(d, g);
                LOG.info(Guardian.MARKER, "/vg command tree registered (deferred from CommandRegistrationCallback)");
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
}
