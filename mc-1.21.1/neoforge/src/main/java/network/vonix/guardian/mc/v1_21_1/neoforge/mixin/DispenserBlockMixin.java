/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures dispenser activation for attribution/rollback.
 *
 * <p>v1.3.0 W1c note: {@code dispenseFrom} is a discrete redstone-triggered event —
 * every call to this method corresponds to exactly one real dispense action.
 * HEAD injection is the correct discipline here (no over-submission risk).
 * Verified vs the tighter Leaves/Ice/ConcretePowder pattern: unlike those,
 * this method never runs on a "nothing happened" fast path, so no @Redirect
 * refinement is required.</p>
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void vg$onDispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        try {
            NeoForgeMixinBridge.dispense(level, pos);
        } catch (Throwable ignored) {
        }
    }
}
