/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.1 X3 — fluid-flow attribution producer.
 *
 * <p><strong>Target class.</strong> Named {@code LiquidBlockMixin} per the
 * CoreProtect-parity {@code BlockFromToListener} vocabulary, but mixes into
 * {@link FlowingFluid} — the vanilla class that owns fluid spread mechanics.
 * The Bukkit-side {@code BlockFromToEvent} fires when a fluid tries to flow
 * into a neighbour block; the closest Mojang-vanilla surface is
 * {@code FlowingFluid.spreadTo}, which is invoked for every destination cell
 * a source block spreads into.</p>
 *
 * <p><strong>What we log.</strong> Each fired {@code spreadTo} produces a
 * {@link network.vonix.guardian.core.action.ActionType#FLUID_FLOW} row with
 * the destination position and the flowing fluid id (water / lava). Rolling
 * back the row clears the destination back to {@code minecraft:air}.</p>
 *
 * <p><strong>Attribution.</strong> The bridge consults
 * {@link network.vonix.guardian.core.attribution.FluidSourceMemory} for a
 * bucket-empty ancestor within the 8-block Manhattan / 2-minute window; if
 * found, the row carries that player's UUID + name. Otherwise it falls back
 * to the {@link network.vonix.guardian.core.event.Sentinel#FLUID} sentinel.
 * The {@code sourceTag} always starts with
 * {@link network.vonix.guardian.core.diagnostics.MixinHotEventFilter#PREFIX_FLUID}
 * so the mixin-hot-events kill-switch short-circuits fluid spam load.</p>
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
            NeoForgeMixinBridge.fluidFlow(serverLevel, pos, self);
        } catch (Throwable ignored) {
            // Never destabilise the fluid tick.
        }
    }
}
