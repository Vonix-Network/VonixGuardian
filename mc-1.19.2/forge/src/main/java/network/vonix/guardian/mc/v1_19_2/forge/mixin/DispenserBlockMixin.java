/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import network.vonix.guardian.mc.v1_19_2.forge.ForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures dispenser activation for attribution. Signature verified against
 * Forge 1.19.2 official mappings: {@code protected void dispenseFrom(ServerLevel, BlockPos)}.
 * Runtime SRG name is {@code m_5824_} (see {@code vg.refmap.json}).
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void vg$onDispense(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        try {
            ForgeMixinBridge.dispense(level, pos);
        } catch (Throwable ignored) {
        }
    }
}
