/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_21_1.common.WorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ==================================================================== X7 TNT-prime memory
    // v1.3.1 X7: record the actor priming a TNT block so the eventual
    // explosion-detonate handler can attribute correctly. See
    // network.vonix.guardian.core.attribution.TntPrimeMemory.

    /**
     * Record a player as the priming actor for the TNT block at {@code pos}.
     *
     * @param player the priming player (never {@code null}); called from
     *               {@code TntBlockMixin.explode(Level,BlockPos,LivingEntity)}
     *               HEAD when the igniter is a Player, and from
     *               {@code PrimedTntEntityMixin.<init>} TAIL when the igniter
     *               argument on the constructor is a Player.
     */
    public static void recordTntPrimePlayer(Level level, BlockPos pos, Player player) {
        try {
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g == null || level == null || pos == null || player == null) return;
            long now = System.currentTimeMillis();
            g.tntPrimeMemory().record(
                    WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                    network.vonix.guardian.core.attribution.TntPrimeMemory.PrimeRecord.player(
                            player.getUUID(), player.getName().getString(), now));
        } catch (Throwable t) {
            warn("recordTntPrimePlayer", t);
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
            s.submitBucketEmpty(player.getUUID(), player.getName().getString(),
                    WorldKey.of(player.level()), pos.getX(), pos.getY(), pos.getZ(),
                    fluidId == null ? "minecraft:water" : fluidId, null);
        } catch (Throwable t) {
            warn("bucketEmpty", t);
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
}
