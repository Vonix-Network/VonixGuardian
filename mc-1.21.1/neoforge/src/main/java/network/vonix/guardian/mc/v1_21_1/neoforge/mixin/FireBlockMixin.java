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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.RandomSource;

/** Captures fire tick (burn side-effects) and fire placement (ignite). */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void vg$onFireTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand, CallbackInfo ci) {
        try {
            NeoForgeMixinBridge.fireBurn(level, pos, state);
        } catch (Throwable ignored) {
            // never let logging break world tick
        }
    }

    @Inject(method = "onPlace", at = @At("HEAD"))
    private void vg$onFirePlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving, CallbackInfo ci) {
        try {
            if (oldState.is(state.getBlock())) return;
            NeoForgeMixinBridge.fireIgnite(level, pos, state);
        } catch (Throwable ignored) {
        }
    }
}
