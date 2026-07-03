/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.1 X3 — fluid-flow attribution producer (Fabric 1.21.1 mirror).
 *
 * <p>See {@code mc-1.21.1/neoforge/.../LiquidBlockMixin} for the parity
 * rationale. Mixes into {@link FlowingFluid#spreadTo} because the
 * Bukkit-side {@code BlockFromToEvent} does not exist on Fabric loaders.</p>
 */
@Mixin(FlowingFluid.class)
public abstract class LiquidBlockMixin {

    @Inject(method = "spreadTo", at = @At("HEAD"))
    private void vg$captureSpread(LevelAccessor level, BlockPos pos, BlockState state,
                                  Direction direction, FluidState fluidState, CallbackInfo ci) {
        try {
            if (level == null || pos == null || fluidState == null) return;
            if (!(level instanceof ServerLevel serverLevel)) return;
            FlowingFluid self = (FlowingFluid) (Object) this;
            FabricMixinBridge.fluidFlow(serverLevel, pos, self);
        } catch (Throwable ignored) {
            // Never destabilise the fluid tick.
        }
    }
}
