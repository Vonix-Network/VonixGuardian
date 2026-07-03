/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures dispenser activations on Fabric. */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void vg$onDispense(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        try {
            FabricMixinBridge.dispense(level, pos);
        } catch (Throwable ignored) {
        }
    }
}
