/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: FallingBlockEntity fall (break) + tick (land). */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @Shadow private BlockState blockState;

    @Redirect(method = "fall", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static boolean vg$fallingBlockFall(Level level, BlockPos pos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlock(pos, newState, flags);
        if (changed && oldState != null && !oldState.isAir()) {
            vg$submitBreak(null, level, pos, oldState, EntitySentinel.SRC_GRAVITY);
        }
        return changed;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$fallingBlockLand(Level level, BlockPos pos, BlockState placedState, int flags) {
        boolean placed = level.setBlock(pos, placedState, flags);
        if (placed && placedState != null && !placedState.isAir()) {
            FallingBlockEntity self = (FallingBlockEntity) (Object) this;
            vg$submitPlace(self, level, pos, placedState, EntitySentinel.SRC_GRAVITY);
        }
        return placed;
    }

    private static void vg$submitBreak(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || oldState == null) return;
            String actor = network.vonix.guardian.mc.v1_19_2.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(oldState), "minecraft:air",
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static void vg$submitPlace(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState newState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || newState == null) return;
            String actor = network.vonix.guardian.mc.v1_19_2.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    "minecraft:air", vg$blockId(newState),
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static void vg$submitChange(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || oldState == null || newState == null) return;
            String actor = network.vonix.guardian.mc.v1_19_2.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(oldState), vg$blockId(newState),
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static String vg$blockId(net.minecraft.world.level.block.state.BlockState state) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.core.Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
