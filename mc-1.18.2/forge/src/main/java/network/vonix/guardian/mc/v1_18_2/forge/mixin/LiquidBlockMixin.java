/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.FluidSourceMemory;
import network.vonix.guardian.core.diagnostics.MixinHotEventFilter;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * v1.3.1 X3 — fluid-flow attribution producer (Forge 1.18.2 source-parity mirror).
 *
 * <p>Source-parity file only: this repo does NOT register Forge mixin JSON at
 * runtime; the production Forge fluid-flow surface remains
 * <code>ForgeEvents.onFillBucket</code> for the bucket-empty seed side. The
 * class is kept structurally identical to the NeoForge / Fabric cells so a
 * future wave that wires Forge mixins can turn it on without a code delta.</p>
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
            vg$submitFluidFlow(serverLevel, pos, self);
        } catch (Throwable ignored) {
        }
    }

    private static void vg$submitFluidFlow(ServerLevel level, BlockPos pos, FlowingFluid flowingFluid) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            String worldId = level.dimension().location().toString();
            String path;
            try {
                ResourceLocation rl = Registry.FLUID.getKey(flowingFluid);
                path = rl == null ? "water" : rl.getPath();
            } catch (Throwable t) {
                path = "water";
            }
            String kind = path.contains("lava") ? "lava" : "water";
            String fluidBlockId = "minecraft:" + kind;
            String sourceTag = MixinHotEventFilter.PREFIX_FLUID + ":" + kind;

            FluidSourceMemory mem = g.fluidSourceMemory();
            UUID actorUuid = null;
            String actorName = Sentinel.FLUID;
            if (mem != null) {
                FluidSourceMemory.Record rec = mem.lookup(worldId, pos.getX(), pos.getY(), pos.getZ(),
                        System.currentTimeMillis());
                if (rec != null && rec.actorUuid != null) {
                    actorUuid = rec.actorUuid;
                    actorName = rec.actorName != null ? rec.actorName : Sentinel.FLUID;
                }
            }
            s.submitFluidFlow(actorUuid, actorName, worldId,
                    pos.getX(), pos.getY(), pos.getZ(), fluidBlockId, sourceTag);
        } catch (Throwable ignored) {
        }
    }
}
