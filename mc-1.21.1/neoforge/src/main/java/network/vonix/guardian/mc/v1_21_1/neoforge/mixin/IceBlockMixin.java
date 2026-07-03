/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures ice melt (fade) side-effect — v1.3.0 W1c:
 * only submit on actual ice→water conversion, not on every {@code melt} HEAD.
 * {@code IceBlock.melt} can early-return in some game rules; @Redirect on the two mutation calls
 * it may make ({@code removeBlock} / {@code setBlockAndUpdate}) is the only way to guarantee
 * the block truly changed before we submit.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Redirect(method = "melt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$meltRemove(Level level, BlockPos pos, boolean moving) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.removeBlock(pos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            try {
                NeoForgeMixinBridge.blockFade(level, pos, oldState);
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    @Redirect(method = "melt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean vg$meltSet(Level level, BlockPos pos, BlockState newState) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlockAndUpdate(pos, newState);
        if (changed && oldState != null && !oldState.isAir() && (newState == null || !oldState.is(newState.getBlock()))) {
            try {
                NeoForgeMixinBridge.blockFade(level, pos, oldState);
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }
}
