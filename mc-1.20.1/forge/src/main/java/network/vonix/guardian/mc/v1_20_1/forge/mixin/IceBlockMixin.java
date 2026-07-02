/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_20_1.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures ice melt (fade) side-effect.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Inject(method = "melt", at = @At("HEAD"))
    private void vg$onMelt(BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            String blockId;
            try {
                ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                blockId = rl != null ? rl.toString() : "minecraft:ice";
            } catch (Throwable t) {
                blockId = "minecraft:ice";
            }
            s.submitFade(null, "#natural",
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId, "world:fade");
        } catch (Throwable ignored) {
        }
    }
}
