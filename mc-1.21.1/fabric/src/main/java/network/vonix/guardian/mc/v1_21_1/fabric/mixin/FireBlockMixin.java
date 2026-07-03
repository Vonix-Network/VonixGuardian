/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures actual fire-caused block removals and fire formation on Fabric. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$fireTickRemove(ServerLevel level, BlockPos targetPos, boolean moving) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.removeBlock(targetPos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            FabricMixinBridge.fireBurn(level, targetPos, oldState);
        }
        return changed;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$fireTickSet(ServerLevel level, BlockPos targetPos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.setBlock(targetPos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                FabricMixinBridge.fireBurn(level, targetPos, oldState);
            } else {
                FabricMixinBridge.fireIgnite(level, targetPos, newState);
            }
        }
        return changed;
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$checkBurnOutRemove(Level level, BlockPos targetPos, boolean moving) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.removeBlock(targetPos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            FabricMixinBridge.fireBurn(level, targetPos, oldState);
        }
        return changed;
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$checkBurnOutSet(Level level, BlockPos targetPos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.setBlock(targetPos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                FabricMixinBridge.fireBurn(level, targetPos, oldState);
            } else {
                FabricMixinBridge.fireIgnite(level, targetPos, newState);
            }
        }
        return changed;
    }

    @Inject(method = "onPlace", at = @At("TAIL"))
    private void vg$onFirePlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving, CallbackInfo ci) {
        try {
            if (state == null || level == null || pos == null) return;
            if (oldState != null && oldState.is(state.getBlock())) return;
            if (level.getBlockState(pos).is(state.getBlock())) {
                FabricMixinBridge.fireIgnite(level, pos, state);
            }
        } catch (Throwable ignored) {
        }
    }
}
