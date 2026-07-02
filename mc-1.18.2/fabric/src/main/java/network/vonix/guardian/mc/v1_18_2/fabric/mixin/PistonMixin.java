/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PISTON — inject at HEAD of {@code PistonBaseBlock#moveBlocks} so we observe
 * the piston BEFORE the movement runs. Fires once per extend/retract.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonMixin {

    @Inject(method = "moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z",
            at = @At("HEAD"),
            require = 0)
    private void vg$onMove(Level level, BlockPos pos, Direction dir, boolean extending,
                           CallbackInfoReturnable<Boolean> cir) {
        try {
            FabricMixinBridge.piston(level, pos, extending);
        } catch (Throwable ignored) {}
    }
}
