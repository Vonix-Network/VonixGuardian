/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures fire tick (burn side-effects) and fire placement (ignite).
 *
 * <p>v1.3.0 W1a — tightened: replaced HEAD injections on {@code tick} that
 * submitted a BURN Action on every random-tick regardless of whether fire
 * actually consumed a block. We now @Redirect the inner
 * {@code level.removeBlock(pos, moving)} / {@code level.setBlock(pos, state, flags)}
 * calls inside {@code tick}/{@code checkBurnOut} and only submit when those
 * calls actually removed / replaced a block. For {@code onPlace} we keep
 * a TAIL inject but verify the placed block is a different block from the
 * old one AND the level actually shows the new state.</p>
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$fireTickRemove(ServerLevel level, BlockPos targetPos, boolean moving) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.removeBlock(targetPos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            NeoForgeMixinBridge.fireBurn(level, targetPos, oldState);
        }
        return changed;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$fireTickSet(ServerLevel level, BlockPos targetPos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.setBlock(targetPos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                NeoForgeMixinBridge.fireBurn(level, targetPos, oldState);
            } else {
                NeoForgeMixinBridge.fireIgnite(level, targetPos, newState);
            }
        }
        return changed;
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$checkBurnOutRemove(Level level, BlockPos targetPos, boolean moving) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.removeBlock(targetPos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            NeoForgeMixinBridge.fireBurn(level, targetPos, oldState);
        }
        return changed;
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$checkBurnOutSet(Level level, BlockPos targetPos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.setBlock(targetPos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                NeoForgeMixinBridge.fireBurn(level, targetPos, oldState);
            } else {
                NeoForgeMixinBridge.fireIgnite(level, targetPos, newState);
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
                NeoForgeMixinBridge.fireIgnite(level, pos, state);
            }
        } catch (Throwable ignored) {
        }
    }
}
