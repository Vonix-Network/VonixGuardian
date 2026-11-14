/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinLoggingSupport;

/**
 * Sugar cane growth — {@code SugarCaneBlock#randomTick} increases {@code age} and,
 * at age 15, places one new stalk block at {@code pos.above()} and resets its own
 * age to 0.
 *
 * <p>The cell above is snapshotted at {@code HEAD} and re-read at {@code RETURN},
 * so an age-only tick — fifteen of every sixteen growth steps — produces no row.
 * Only a real new block above is reported, as
 * {@link network.vonix.guardian.core.action.ActionType#STRUCTURE_GROW}, whose
 * inverse clears the grown cell.</p>
 */
@Mixin(SugarCaneBlock.class)
public abstract class SugarCaneGrowthMixin {

    @Inject(
            method = "m_7455_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeCaneTick(BlockState state, ServerLevel level, BlockPos pos,
                                   Random random, CallbackInfo ci) {
        MixinLoggingSupport.pushWatch(MixinLoggingSupport.SUGAR_CANE_WATCHES, level, pos == null ? null : pos.above(), false);
    }

    @Inject(
            method = "m_7455_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterCaneTick(BlockState state, ServerLevel level, BlockPos pos,
                                  Random random, CallbackInfo ci) {
        MixinLoggingSupport.drainWatches(MixinLoggingSupport.SUGAR_CANE_WATCHES, level, 1, "#natural:sugar_cane");
    }
}
