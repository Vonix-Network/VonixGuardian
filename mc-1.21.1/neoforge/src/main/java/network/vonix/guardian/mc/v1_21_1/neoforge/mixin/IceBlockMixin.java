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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures ice melt (fade) side-effect. */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Inject(method = "melt", at = @At("HEAD"))
    private void vg$onMelt(BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        try {
            NeoForgeMixinBridge.blockFade(level, pos, state);
        } catch (Throwable ignored) {
        }
    }
}
