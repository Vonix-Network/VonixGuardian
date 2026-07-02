/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_21_1.neoforge.VonixGuardianNeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.RandomSource;

/**
 * Captures grass/mycelium/podzol spread events.
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {

    @Inject(method = "randomTick", at = @At("HEAD"))
    private void vg$onSpread(BlockState state, ServerLevel level, BlockPos pos, RandomSource rand, CallbackInfo ci) {
        try {
            Guardian g = VonixGuardianNeoForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            String blockId;
            try {
                ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                blockId = rl != null ? rl.toString() : "minecraft:grass_block";
            } catch (Throwable t) {
                blockId = "minecraft:grass_block";
            }
            s.submitSpread(null, "#natural",
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId, "world:spread");
        } catch (Throwable ignored) {
        }
    }
}
