/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.IpHasher;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v1_20_1.common.ChatRenderer;
import network.vonix.guardian.mc.v1_20_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_20_1.common.GuardianCommands;
import network.vonix.guardian.mc.v1_20_1.common.Inspector;
import network.vonix.guardian.mc.v1_20_1.common.SourceTagger;
import network.vonix.guardian.mc.v1_20_1.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Map<String, Long> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

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
                    WorldKey.of(e.level()),
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
            Entity source = ev.getExplosion().getDirectSourceEntity();
            Attribution attr = (source != null && ForgeBootstrap.resolver != null)
                    ? ForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
            Vec3 c = ev.getExplosion().getPosition();
            BlockPos center = BlockPos.containing(c);
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
            s.submitEntityKill(attr.actorUuid(), attr.actorName(),
                    WorldKey.of(victim.level()),
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
            String type = EntitySentinel.of(e);
            long now = System.currentTimeMillis();
            Long last = SPAWN_LIMIT.get(type);
            if (last != null && now - last < SPAWN_LIMIT_MS) return;
            SPAWN_LIMIT.put(type, now);
            BlockPos pos = e.blockPosition();
            s.submitEntitySpawn(null, type,
                    WorldKey.of(ev.getLevel()),
                    pos.getX(), pos.getY(), pos.getZ(), type, null);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onEntityJoinLevel failed", t);
        }
    }

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

    @SubscribeEvent
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
            s.submitItemDrop(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), null);
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

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock ev) {
        try {
            EventSubmitter s = sub();
            GuardianConfig c = cfg();
            if (s == null || c == null) return;
            if (!c.actions().logInteractions()) return;
            Player p = ev.getEntity();
            if (p == null) return;
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
                sp.sendSystemMessage(ChatRenderer.primary(g.theme(),
                        "[VonixGuardian] inspect @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
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
            if (ev.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND) {
                s.submitPistonExtend(null, Sentinel.PISTON, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), blockId, Sentinel.PISTON);
            } else {
                s.submitPistonRetract(null, Sentinel.PISTON, worldId,
                        pos.getX(), pos.getY(), pos.getZ(), blockId, Sentinel.PISTON);
            }
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "onPistonPre failed", t);
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
