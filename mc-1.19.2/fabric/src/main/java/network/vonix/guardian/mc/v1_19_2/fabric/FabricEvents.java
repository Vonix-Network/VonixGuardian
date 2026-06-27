/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import network.vonix.guardian.mc.v1_19_2.common.ChatRenderer;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_19_2.common.GuardianCommands;
import network.vonix.guardian.mc.v1_19_2.common.Inspector;
import network.vonix.guardian.mc.v1_19_2.common.SourceTagger;
import network.vonix.guardian.mc.v1_19_2.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric event handlers translating Fabric API callbacks into
 * {@link EventSubmitter} calls per SHARED-LOADER-CONTRACTS § 3.
 *
 * <p>Every handler catches {@link Throwable} and logs via {@code VONIXGUARDIAN};
 * exceptions never escape into the server thread.
 *
 * <p><b>v0.1.0 Fabric gaps</b> (no first-class Fabric API event): LivingDestroyBlock
 * (universal mob griefing), Piston pre, LeavesDecay, NeighborNotify (fire spread,
 * fluid flow), Explosion detonate, ItemToss / ItemPickup / ItemCraft, SignChange.
 * These will be covered by mixins in v0.2.0; for v0.1.0 we cover the player-driven
 * surface (break, place via UseBlock proxy, click, death, spawn, join/leave, chat,
 * command, container interaction).
 */
public final class FabricEvents {

    private static final Logger LOG = LoggerFactory.getLogger(FabricEvents.class);

    /** Per-entity-type rate limit for spawn logging (last-emit ts in millis). */
    private static final Map<String, Long> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    private FabricEvents() {
        // utility
    }

    /** Hook all Fabric API callbacks. Called once from the mod entrypoint. */
    public static void register() {
        PlayerBlockBreakEvents.AFTER.register(FabricEvents::onBlockBreakAfter);
        UseBlockCallback.EVENT.register(FabricEvents::onUseBlock);
        AttackBlockCallback.EVENT.register(FabricEvents::onAttackBlock);

        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH
                .register(FabricEvents::onAfterDeath);
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE
                .register(FabricEvents::onAllowDamage);

        ServerEntityEvents.ENTITY_LOAD.register(FabricEvents::onEntityLoad);

        ServerPlayConnectionEvents.JOIN.register(FabricEvents::onJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(FabricEvents::onDisconnect);

        ServerMessageEvents.CHAT_MESSAGE.register(FabricEvents::onChatMessage);
        ServerMessageEvents.COMMAND_MESSAGE.register(FabricEvents::onCommandMessage);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            try {
                Guardian g = VonixGuardianFabric.guardian();
                if (g == null) {
                    LOG.warn(Guardian.MARKER, "Commands fired before Guardian.boot — skipping");
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

    /**
     * {@code PlayerBlockBreakEvents.AFTER} → BLOCK_BREAK row. The BEFORE event is
     * used by Inspector via {@link #onAttackBlock} (left-click cancel), so this
     * fires only when the break has been committed.
     */
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

    /**
     * Right-click block → CLICK row if {@code logInteractions}. Fabric also fires
     * this for block-placement intent (the actual placed-block event has no
     * loader-agnostic surface; we approximate via the click and rely on the
     * AFTER break for sibling completeness).
     *
     * <p>TODO(v0.2.0): mixin BlockItem#place so we capture real BlockPlace events.
     */
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
     * Left-click block → if the player is inspecting, swallow the swing and emit
     * a chat-feedback line. The break event will not fire because we return
     * {@link InteractionResult#SUCCESS} to claim the interaction.
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
            if (gg != null && player instanceof ServerPlayer sp) {
                // Re-sync client; cancelling the swing doesn't always restore the block client-side.
                sp.connection.send(new ClientboundBlockUpdatePacket(pos, world.getBlockState(pos)));
                sp.sendSystemMessage(ChatRenderer.primary(gg.theme(),
                        "[VonixGuardian] inspect @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
                // TODO: wire a proper position-lookup query once QueryParser supports `p:x,y,z`.
            }
            return InteractionResult.SUCCESS;
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAttackBlock failed", t);
            return InteractionResult.PASS;
        }
    }

    // ====================================================================== combat

    /** Record player attackers into the damage history (universal attribution step 4). */
    private static boolean onAllowDamage(LivingEntity victim,
                                         net.minecraft.world.damagesource.DamageSource source,
                                         float amount) {
        try {
            if (FabricBootstrap.damageHistory == null || victim == null || source == null) {
                return true;
            }
            Entity src = source.getEntity();
            if (src instanceof Player p) {
                FabricBootstrap.damageHistory.record(victim.getUUID(), p.getUUID(),
                        System.currentTimeMillis());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAllowDamage failed", t);
        }
        return true; // never block damage — we only observe
    }

    /** Log entity deaths via universal attribution. */
    private static void onAfterDeath(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
        try {
            EventSubmitter s = sub();
            if (s == null || victim == null) return;
            Entity killer = source == null ? null : source.getEntity();
            Attribution attr;
            if (killer != null && FabricBootstrap.resolver != null) {
                attr = FabricBootstrap.resolver.resolve(killer, System.currentTimeMillis());
            } else {
                attr = Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            BlockPos pos = victim.blockPosition();
            String entityType = EntitySentinel.of(victim);
            s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(victim.level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    entityType, source == null ? null : SourceTagger.tag(source));
            if (FabricBootstrap.damageHistory != null) {
                FabricBootstrap.damageHistory.forget(victim.getUUID());
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onAfterDeath failed", t);
        }
    }

    // ====================================================================== entities

    /** Log non-player entity spawns; rate-limited per entity-type. */
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
            ServerPlayer p = handler.getPlayer();
            if (p == null) return;
            String addr;
            try {
                addr = handler.connection.getRemoteAddress() != null
                        ? handler.connection.getRemoteAddress().toString()
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
            LOG.warn(Guardian.MARKER, "onJoin failed", t);
        }
    }

    private static void onDisconnect(net.minecraft.server.network.ServerGamePacketListenerImpl handler,
                                     net.minecraft.server.MinecraftServer server) {
        try {
            EventSubmitter s = sub();
            if (s == null || handler == null) return;
            ServerPlayer p = handler.getPlayer();
            if (p == null) return;
            s.submitSessionLeave(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level), "logout");
            Inspector.forget(p.getUUID());
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onDisconnect failed", t);
        }
    }

    // ====================================================================== chat / commands

    private static void onChatMessage(net.minecraft.network.chat.PlayerChatMessage message,
                                      ServerPlayer sender,
                                      net.minecraft.network.chat.ChatType.Bound params) {
        try {
            EventSubmitter s = sub();
            if (s == null || sender == null || message == null) return;
            String text = message.signedContent().plain();
            s.submitChat(sender.getUUID(), sender.getName().getString(),
                    WorldKey.of(sender.level), text);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onChatMessage failed", t);
        }
    }

    private static void onCommandMessage(net.minecraft.network.chat.PlayerChatMessage message,
                                         net.minecraft.commands.CommandSourceStack source,
                                         net.minecraft.network.chat.ChatType.Bound params) {
        try {
            EventSubmitter s = sub();
            if (s == null || message == null || source == null) return;
            String raw = message.signedContent().plain();
            if (source.getEntity() instanceof ServerPlayer p) {
                s.submitCommand(p.getUUID(), p.getName().getString(),
                        WorldKey.of(p.level), "/" + raw);
            } else {
                s.submitCommand(null, "#console", "minecraft:overworld", "/" + raw);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onCommandMessage failed", t);
        }
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
