/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Coral plant drying out — {@code CoralPlantBlock#tick} replaces a live
 * {@code coral} with {@code dead_coral} (keeping its {@code waterlogged} value)
 * when no water is nearby.
 *
 * <p>Same RETURN-and-re-read discipline as {@link CoralBlockDeathMixin}: the
 * emitted row is verified against the world rather than assumed from vanilla's
 * control flow, so a {@code require = 1} apply failure is the only way this
 * silently stops reporting.</p>
 */
@Mixin(CoralPlantBlock.class)
public abstract class CoralPlantDeathMixin {

    @Inject(
            method = "m_7458_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterCoralPlantTick(BlockState state, ServerLevel level, BlockPos pos,
                                        Random random, CallbackInfo ci) {
        try {
            if (level == null || pos == null || state == null) return;
            MixinLoggingSupport.emitFade(level, pos, state, level.getBlockState(pos), "#natural:coral");
        } catch (Throwable ignored) {
        }
    }
}
