/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CoralFanBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Coral fan drying out — {@code CoralFanBlock#tick} replaces a live
 * {@code coral_fan} with {@code dead_coral_fan} when no water is nearby.
 *
 * <p>Same RETURN-and-re-read discipline as {@link CoralBlockDeathMixin}. Fans
 * override {@code tick} on the concrete class rather than inheriting a shared
 * base implementation, which is why the plant, fan and wall-fan families each
 * get their own injector.</p>
 */
@Mixin(CoralFanBlock.class)
public abstract class CoralFanDeathMixin {

    @Inject(
            method = "m_7458_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterCoralFanTick(BlockState state, ServerLevel level, BlockPos pos,
                                      Random random, CallbackInfo ci) {
        try {
            if (level == null || pos == null || state == null) return;
            MixinLoggingSupport.emitFade(level, pos, state, level.getBlockState(pos), "#natural:coral");
        } catch (Throwable ignored) {
        }
    }
}
