/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinLoggingSupport;

/**
 * Coral drying out — {@code CoralBlock#tick} turns solid coral into its
 * {@code dead_*} counterpart when no water is within range.
 *
 * <p>The row is emitted at {@code RETURN} by re-reading the block at {@code pos}
 * and comparing it with the {@code state} the tick was scheduled for. That is
 * deliberately descriptor-stable: it does not depend on which
 * {@code Level#setBlock} overload vanilla calls, and it self-verifies, so a row
 * appears only when the block really changed kind. A {@code waterlogged}
 * property flip on the same block is not a death and is ignored by
 * {@link MixinLoggingSupport#emitFade}.</p>
 *
 * <p>Emitted as {@link network.vonix.guardian.core.action.ActionType#FADE}
 * carrying the live coral id; FADE's inverse restores that block, so a rollback
 * revives the coral.</p>
 */
@Mixin(CoralBlock.class)
public abstract class CoralBlockDeathMixin {

    @Inject(
            method = "m_7458_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterCoralBlockTick(BlockState state, ServerLevel level, BlockPos pos,
                                        Random random, CallbackInfo ci) {
        try {
            if (level == null || pos == null || state == null) return;
            MixinLoggingSupport.emitFade(level, pos, state, level.getBlockState(pos), "#natural:coral");
        } catch (Throwable ignored) {
        }
    }
}
