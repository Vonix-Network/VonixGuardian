/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.RandomSource;

/** Captures grass/mycelium spread ticks. */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void vg$onSpread(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand, CallbackInfo ci) {
        try {
            NeoForgeMixinBridge.blockSpread(level, pos, state);
        } catch (Throwable ignored) {
        }
    }
}
