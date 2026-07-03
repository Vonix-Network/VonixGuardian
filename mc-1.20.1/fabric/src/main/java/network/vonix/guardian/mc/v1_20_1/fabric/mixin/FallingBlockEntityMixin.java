/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_20_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: FallingBlockEntity fall (break at source) + tick (place at land). */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @Shadow private BlockState blockState;

    @Redirect(method = "fall", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static boolean vg$fallingBlockFall(Level level, BlockPos pos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlock(pos, newState, flags);
        if (changed && oldState != null && !oldState.isAir()) {
            FabricMixinBridge.entityBreak(null, level, pos, oldState, EntitySentinel.SRC_GRAVITY);
        }
        return changed;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$fallingBlockLand(Level level, BlockPos pos, BlockState placedState, int flags) {
        boolean placed = level.setBlock(pos, placedState, flags);
        if (placed && placedState != null && !placedState.isAir()) {
            FallingBlockEntity self = (FallingBlockEntity) (Object) this;
            FabricMixinBridge.entityPlace(self, level, pos, placedState, EntitySentinel.SRC_GRAVITY);
        }
        return placed;
    }
}
