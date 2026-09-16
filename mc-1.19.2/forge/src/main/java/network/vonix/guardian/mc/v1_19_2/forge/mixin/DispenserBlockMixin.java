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
 * Captures dispenser activation for attribution. Packaged Forge 1.19.2 runtime
 * exposes {@code protected void m_5824_(ServerLevel, BlockPos)}; bind that SRG
 * name with {@code remap = false} so the injector does not depend on a named
 * {@code dispenseFrom} refmap lookup.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(
            method = "m_5824_(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$onDispense(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        try {
            ForgeMixinBridge.dispense(level, pos);
        } catch (Throwable ignored) {
        }
    }
}
