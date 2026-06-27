/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric;

import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.IpHasher;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_18_2.common.ChatRenderer;
import network.vonix.guardian.mc.v1_18_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_18_2.common.GuardianCommands;
import network.vonix.guardian.mc.v1_18_2.common.Inspector;
import network.vonix.guardian.mc.v1_18_2.common.SourceTagger;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric event handlers for 1.18.2.
 *
 * <p><b>Gaps relative to 1.20.1 Fabric port:</b>
 * <ul>
 *   <li><b>Chat capture is SKIPPED</b>: fabric-api 0.77.0+1.18.2 has no
 *       {@code ServerMessageEvents.CHAT_MESSAGE} API (that arrived in 1.19+).
 *       v0.2.0 will use {@code ServerPlayConnectionEvents} or a Mixin into
 *       {@code ServerGamePacketListenerImpl#handleChat}.</li>
 *   <li>Command capture also skipped (no {@code COMMAND_MESSAGE} event); will be
 *       added via Mixin in v0.2.0. The {@code CommandRegistrationCallback} still
 *       fires to register the {@code /vg} tree.</li>
 *   <li>LivingDestroyBlock / Piston / LeavesDecay / NeighborNotify / Explosion
 *       detonate / ItemToss / ItemPickup / ItemCraft / SignChange — same gaps as
 *       1.20.1 Fabric port; reserved for v0.2.0 mixins.</li>
 * </ul>
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

    private static final Map<String, Long> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    private FabricEvents() {
        // utility
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register(FabricEvents::onBlockBreakAfter);
        UseBlockCallback.EVENT.register(FabricEvents::onUseBlock);
        AttackBlockCallback.EVENT.register(FabricEvents::onAttackBlock);

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(FabricEvents::onAfterKilledOther);

        ServerEntityEvents.ENTITY_LOAD.register(FabricEvents::onEntityLoad);

        ServerPlayConnectionEvents.JOIN.register(FabricEvents::onJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(FabricEvents::onDisconnect);

        // TODO(v0.2.0): chat + command capture via Mixin into
        // ServerGamePacketListenerImpl#handleChat — 1.18.2 fabric-api 0.77.0
        // has no ServerMessageEvents API.
        //
        // TODO(v0.2.0): damage-history population for indirect attribution and
        // natural-death logging — 1.18.2 fabric-api has no
        // ServerLivingEntityEvents.ALLOW_DAMAGE / AFTER_DEATH; needs a Mixin into
        // LivingEntity#actuallyHurt or LivingEntity#die.

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

    // ====================================================================== blocks

    private static void onBlockBreakAfter(Level world, Player player, BlockPos pos,
                                          BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null) return;
            s.submitBlockBreak(player.getUUID(), player.getName().getString(),
                    WorldKey.of(world),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(state), null);
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

    private static InteractionResult onAttackBlock(Player player,
                                                   Level world,
                                                   net.minecraft.world.InteractionHand hand,
                                                   BlockPos pos,
                                                   net.minecraft.core.Direction direction) {
        try {
            if (player == null) return InteractionResult.PASS;
            if (!Inspector.isInspecting(player.getUUID())) return InteractionResult.PASS;
            Guardian gg = g();
            if (gg != null && player instanceof ServerPlayer sp) {
                sp.connection.send(new ClientboundBlockUpdatePacket(pos, world.getBlockState(pos)));
                // 1.18.2: sendMessage(Component, UUID) — no sendSystemMessage yet.
                sp.sendMessage(ChatRenderer.primary(gg.theme(),
                        "[VonixGuardian] inspect @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()),
                        Util.NIL_UUID);
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
     * NOT covered here — they need a Mixin in v0.2.0.
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

    private static void onEntityLoad(Entity entity, net.minecraft.server.level.ServerLevel level) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null || entity == null) return;
            if (!c.actions().logEntities()) return;
            if (!(entity instanceof LivingEntity) || entity instanceof Player) return;
            String type = EntitySentinel.of(entity);
            long now = System.currentTimeMillis();
            Long last = SPAWN_LIMIT.get(type);
            if (last != null && now - last < SPAWN_LIMIT_MS) return;
            SPAWN_LIMIT.put(type, now);
            BlockPos pos = entity.blockPosition();
            s.submitEntitySpawn(null, type,
                    WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), type, null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onEntityLoad failed", t);
        }
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
