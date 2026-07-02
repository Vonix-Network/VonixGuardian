/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_20_1.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.RandomSource;

/**
 * Captures vanilla leaves natural decay.
 *
 * <p>1.18.2 has both {@code tick(BlockState, ServerLevel, BlockPos, Random)} and
 * {@code randomTick(BlockState, ServerLevel, BlockPos, Random)} on LeavesBlock;
 * decay executes in {@code tick} when {@code decaying(state)} is true.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void vg$onLeavesTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand, CallbackInfo ci) {
        try {
            // Only log the tick that actually decays.
            if (state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT)) return;
            if (state.hasProperty(BlockStateProperties.DISTANCE) && state.getValue(BlockStateProperties.DISTANCE) < 7) return;
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            String blockId;
            try {
                ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                blockId = rl != null ? rl.toString() : "minecraft:oak_leaves";
            } catch (Throwable t) {
                blockId = "minecraft:oak_leaves";
            }
            s.submitLeavesDecay(null, "#natural",
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId, "world:leavesdecay");
        } catch (Throwable ignored) {
        }
    }
}
