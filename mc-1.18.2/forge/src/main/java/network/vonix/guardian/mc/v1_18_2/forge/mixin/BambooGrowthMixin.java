/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BambooBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Bamboo growth — {@code BambooBlock#randomTick} extends the stalk upward through
 * {@code growBamboo(BlockState, Level, BlockPos, Random, int)}.
 *
 * <p>The cell above is snapshotted at {@code HEAD} and re-read at {@code RETURN}.
 * That comparison is what keeps the log honest: the same tick may also rewrite the
 * {@code leaves} property on the two stalks below and bump {@code age}, and it may
 * decline to grow at all (16-block height cap, blocked cell, or a
 * {@code ForgeHooks.onCropsGrowPre} veto). Only a genuinely new block above is
 * reported.</p>
 *
 * <p>Emitted as {@link network.vonix.guardian.core.action.ActionType#STRUCTURE_GROW};
 * its inverse clears the grown cell, which is the correct rollback of a stalk that
 * appeared on its own.</p>
 *
 * <p>Binding: {@code randomTick} is SRG {@code m_7455_} on this class. Literal
 * binding with {@code remap = false} plus {@code require = 1} means a mapping
 * change aborts mixin application instead of quietly disabling the producer.</p>
 */
@Mixin(BambooBlock.class)
public abstract class BambooGrowthMixin {

    @Inject(
            method = "m_7455_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeBambooTick(BlockState state, ServerLevel level, BlockPos pos,
                                     Random random, CallbackInfo ci) {
        MixinLoggingSupport.pushWatch(MixinLoggingSupport.BAMBOO_WATCHES, level, pos == null ? null : pos.above(), false);
    }

    @Inject(
            method = "m_7455_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterBambooTick(BlockState state, ServerLevel level, BlockPos pos,
                                    Random random, CallbackInfo ci) {
        MixinLoggingSupport.drainWatches(MixinLoggingSupport.BAMBOO_WATCHES, level, 1, "#natural:bamboo");
    }
}
