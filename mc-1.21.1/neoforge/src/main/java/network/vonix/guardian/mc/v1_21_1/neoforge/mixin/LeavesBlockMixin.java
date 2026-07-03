/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.RandomSource;

/** Captures vanilla leaves natural decay. */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void vg$onLeavesTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand, CallbackInfo ci) {
        try {
            if (state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT)) return;
            if (state.hasProperty(BlockStateProperties.DISTANCE) && state.getValue(BlockStateProperties.DISTANCE) < 7) return;
            NeoForgeMixinBridge.leavesDecay(level, pos, state);
        } catch (Throwable ignored) {
        }
    }
}
