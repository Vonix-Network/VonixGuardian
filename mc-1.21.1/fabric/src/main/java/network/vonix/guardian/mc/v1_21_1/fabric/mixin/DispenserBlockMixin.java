/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures dispenser activations on Fabric.
 *
 * <p>v1.3.0 W1c note: {@code dispenseFrom} is a discrete redstone-triggered event —
 * every call corresponds to exactly one real dispense action. HEAD injection is the
 * correct discipline; no @Redirect refinement needed (unlike Leaves/Ice/ConcretePowder,
 * this method never runs on a no-op fast path).</p>
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void vg$onDispense(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        try {
            FabricMixinBridge.dispense(level, pos);
        } catch (Throwable ignored) {
        }
    }
}
