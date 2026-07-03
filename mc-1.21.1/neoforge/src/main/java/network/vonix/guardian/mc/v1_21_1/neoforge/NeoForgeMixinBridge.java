/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.FluidSourceMemory;
import network.vonix.guardian.core.diagnostics.MixinHotEventFilter;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v1_21_1.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.entity.Entity;
import network.vonix.guardian.mc.v1_21_1.common.EntitySentinel;

/**
 * Non-mixin bridge for NeoForge mixins.
 *
 * <p>Mixin classes intentionally call only this bridge and never reference core
 * classes directly. Mixin pre-processing happens before the shaded core classes
 * are visible in the NeoForge dev-launch classpath; direct method references to
 * {@code network.vonix.guardian.core.*} crash boot. Keeping those references in
 * this normal mod class makes the mixin config boot-safe while preserving the
 * same EventSubmitter contract.</p>
 */
public final class NeoForgeMixinBridge {
    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeMixinBridge.class);

    private NeoForgeMixinBridge() {}

    private static EventSubmitter sub() {
        Guardian g = VonixGuardianNeoForge.guardian();
        return g == null ? null : g.submitter();
    }

    public static void fireBurn(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitBurn(null, "#fire", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:burn");
        } catch (Throwable t) {
            warn("fireBurn", t);
        }
    }

    public static void fireIgnite(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitIgnite(null, "#fire", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:ignite");
        } catch (Throwable t) {
            warn("fireIgnite", t);
        }
    }

    public static void blockFade(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitFade(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:fade");
        } catch (Throwable t) {
            warn("blockFade", t);
        }
    }

    public static void leavesDecay(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitLeavesDecay(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:leavesdecay");
        } catch (Throwable t) {
            warn("leavesDecay", t);
        }
    }

    public static void blockSpread(Level level, BlockPos pos, BlockState state) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitSpread(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state), "world:spread");
        } catch (Throwable t) {
            warn("blockSpread", t);
        }
    }

    public static void blockForm(Level level, BlockPos pos, BlockState state, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || state == null) return;
            s.submitForm(null, "#natural", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), blockId(state),
                    sourceTag == null ? "world:form" : sourceTag);
        } catch (Throwable t) {
            warn("blockForm", t);
        }
    }

    public static void dispense(Level level, BlockPos pos) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null) return;
            s.submitDispense(null, "#dispenser", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), "minecraft:dispenser", "world:dispense");
        } catch (Throwable t) {
            warn("dispense", t);
        }
    }

    public static void bucketFill(Player player, BlockPos pos, String fluidId) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || pos == null) return;
            s.submitBucketFill(player.getUUID(), player.getName().getString(),
                    WorldKey.of(player.level()), pos.getX(), pos.getY(), pos.getZ(),
                    fluidId == null ? "minecraft:water" : fluidId, null);
        } catch (Throwable t) {
            warn("bucketFill", t);
        }
    }

    public static void bucketEmpty(Player player, BlockPos pos, String fluidId) {
        try {
            EventSubmitter s = sub();
            if (s == null || player == null || pos == null) return;
            String worldId = WorldKey.of(player.level());
            s.submitBucketEmpty(player.getUUID(), player.getName().getString(),
                    worldId, pos.getX(), pos.getY(), pos.getZ(),
                    fluidId == null ? "minecraft:water" : fluidId, null);
            // v1.3.1 X3: seed the 2-min traceback so downstream fluid-flow
            // rows within radius can attribute back to this player.
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g != null) {
                FluidSourceMemory mem = g.fluidSourceMemory();
                if (mem != null) {
                    mem.recordBucketEmpty(worldId, pos.getX(), pos.getY(), pos.getZ(),
                            player.getUUID(), player.getName().getString(),
                            System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            warn("bucketEmpty", t);
        }
    }

    /**
     * v1.3.1 X3: fluid-flow producer entry.
     *
     * @param level        the server level (spread cell world)
     * @param pos          the destination position that will now hold the
     *                     flowing fluid
     * @param flowingFluid the fluid that is spreading; used to resolve the
     *                     water/lava registry id
     */
    public static void fluidFlow(ServerLevel level, BlockPos pos, FlowingFluid flowingFluid) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || flowingFluid == null) return;
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g == null) return;
            String worldId = WorldKey.of(level);
            String fluidBlockId = fluidBlockId(flowingFluid);
            String kind = fluidKind(flowingFluid); // "water" or "lava"
            String sourceTag = MixinHotEventFilter.PREFIX_FLUID + ":" + kind;

            // Attribution: try 2-min bucket traceback first.
            FluidSourceMemory mem = g.fluidSourceMemory();
            java.util.UUID actorUuid = null;
            String actorName = Sentinel.FLUID;
            if (mem != null) {
                FluidSourceMemory.Record rec = mem.lookup(worldId, pos.getX(), pos.getY(), pos.getZ(),
                        System.currentTimeMillis());
                if (rec != null && rec.actorUuid != null) {
                    actorUuid = rec.actorUuid;
                    actorName = rec.actorName != null ? rec.actorName : Sentinel.FLUID;
                }
            }
            s.submitFluidFlow(actorUuid, actorName, worldId,
                    pos.getX(), pos.getY(), pos.getZ(), fluidBlockId, sourceTag);
        } catch (Throwable t) {
            warn("fluidFlow", t);
        }
    }

    private static String fluidBlockId(Fluid fluid) {
        try {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fluid);
            if (rl == null) return "minecraft:water";
            String path = rl.getPath();
            if (path.contains("lava")) return "minecraft:lava";
            return "minecraft:water";
        } catch (Throwable t) {
            return "minecraft:water";
        }
    }

    private static String fluidKind(Fluid fluid) {
        try {
            ResourceLocation rl = BuiltInRegistries.FLUID.getKey(fluid);
            if (rl == null) return "water";
            return rl.getPath().contains("lava") ? "lava" : "water";
        } catch (Throwable t) {
            return "water";
        }
    }

    public static String blockId(BlockState state) {
        try {
            ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(Item item) {
        try {
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(ItemStack stack) {
        return stack == null ? "minecraft:air" : itemId(stack.getItem());
    }

    private static void warn(String path, Throwable t) {
        LOG.warn("NeoForgeMixinBridge {} failed: {}", path, t.toString());
    }
    // ================================================================== v1.3.1 X2 dispatchers
    
        /**
         * Entity-caused block break — used by the 6 X2 mixins (EnderDragon, Ravager,
         * FallingBlockEntity fall side, Silverfish infest, LightningBolt fire spread
         * cleanup path if applicable). Produces an ENTITY_CHANGE_BLOCK action with
         * a mob-scoped {@code actorName} derived from the entity's registry type and
         * a stable {@code sourceTag} that mirrors the Ledger source constants.
         *
         * @param entity     the entity that caused the break; may be {@code null}
         * @param level      dimension the break happened in
         * @param pos        block position
         * @param oldState   block state before removal (used to produce {@code oldBlockId})
         * @param sourceTag  one of the {@code EntitySentinel.SRC_*} constants
         */
        public static void entityBreak(Entity entity, Level level, BlockPos pos, BlockState oldState, String sourceTag) {
            try {
                EventSubmitter s = sub();
                if (s == null || level == null || pos == null || oldState == null) return;
                String actor = EntitySentinel.of(entity);
                s.submitEntityChangeBlock(null, actor,
                        WorldKey.of(level),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId(oldState), "minecraft:air",
                        sourceTag == null ? actor : sourceTag);
            } catch (Throwable t) {
                warn("entityBreak", t);
            }
        }

    /**
         * Full old→new state change — used by Silverfish infest, which swaps stone→
         * infested_stone (i.e. block-id changes, not break-then-place).
         */
        public static void entityChange(Entity entity, Level level, BlockPos pos, BlockState oldState, BlockState newState, String sourceTag) {
            try {
                EventSubmitter s = sub();
                if (s == null || level == null || pos == null || oldState == null || newState == null) return;
                String actor = EntitySentinel.of(entity);
                s.submitEntityChangeBlock(null, actor,
                        WorldKey.of(level),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId(oldState), blockId(newState),
                        sourceTag == null ? actor : sourceTag);
            } catch (Throwable t) {
                warn("entityChange", t);
            }
        }

    /**
         * Entity-caused block place — used by SnowGolem (aiStep snow trail),
         * FallingBlockEntity landing, and LightningBolt spawnFire.
         *
         * @param entity     the entity that placed the block; may be {@code null}
         * @param level      dimension
         * @param pos        block position
         * @param newState   block state placed (used to produce {@code newBlockId})
         * @param sourceTag  one of the {@code EntitySentinel.SRC_*} constants
         */
        public static void entityPlace(Entity entity, Level level, BlockPos pos, BlockState newState, String sourceTag) {
            try {
                EventSubmitter s = sub();
                if (s == null || level == null || pos == null || newState == null) return;
                String actor = EntitySentinel.of(entity);
                s.submitEntityChangeBlock(null, actor,
                        WorldKey.of(level),
                        pos.getX(), pos.getY(), pos.getZ(),
                        "minecraft:air", blockId(newState),
                        sourceTag == null ? actor : sourceTag);
            } catch (Throwable t) {
                warn("entityPlace", t);
            }
        }

}
