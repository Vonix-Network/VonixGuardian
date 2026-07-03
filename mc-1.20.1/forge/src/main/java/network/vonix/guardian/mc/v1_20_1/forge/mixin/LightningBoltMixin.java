/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_20_1.common.EntitySentinel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: LightningBolt.spawnFire. */
@Mixin(LightningBolt.class)
public abstract class LightningBoltMixin {
    @Redirect(method = "spawnFire", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean vg$lightningFireSpawn(ServerLevel level, BlockPos pos, BlockState newState) {
        boolean placed = level.setBlockAndUpdate(pos, newState);
        if (placed && newState != null && !newState.isAir()) {
            LightningBolt self = (LightningBolt) (Object) this;
            vg$submitPlace(self, level, pos, newState, EntitySentinel.SRC_LIGHTNING);
        }
        return placed;
    }

    private static void vg$submitBreak(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_20_1.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || oldState == null) return;
            String actor = network.vonix.guardian.mc.v1_20_1.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(oldState), "minecraft:air",
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static void vg$submitPlace(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState newState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_20_1.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || newState == null) return;
            String actor = network.vonix.guardian.mc.v1_20_1.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    "minecraft:air", vg$blockId(newState),
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static void vg$submitChange(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_20_1.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || oldState == null || newState == null) return;
            String actor = network.vonix.guardian.mc.v1_20_1.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(oldState), vg$blockId(newState),
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static String vg$blockId(net.minecraft.world.level.block.state.BlockState state) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
