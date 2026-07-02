/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_20_1.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures dispenser activation for attribution/rollback.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"))
    private void vg$onDispense(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            s.submitDispense(null, "#dispenser",
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    "minecraft:dispenser", "world:dispense");
        } catch (Throwable ignored) {
        }
    }
}
