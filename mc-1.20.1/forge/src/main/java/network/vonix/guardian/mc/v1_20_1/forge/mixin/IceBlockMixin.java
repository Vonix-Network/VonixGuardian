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
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures ice melt (fade) side-effect — v1.3.0 W1c:
 * only submit on actual ice→water conversion, not on every {@code melt} HEAD.
 * IceBlock.melt can early-return in some game rules; @Redirect on the two mutation calls
 * it may make (removeBlock / setBlockAndUpdate) is the only way to guarantee
 * the block truly changed before we submit.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMixin {

    @Redirect(method = "melt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$meltRemove(Level level, BlockPos pos, boolean moving) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.removeBlock(pos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            vg$submit(level, pos, oldState);
        }
        return changed;
    }

    @Redirect(method = "melt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean vg$meltSet(Level level, BlockPos pos, BlockState newState) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlockAndUpdate(pos, newState);
        if (changed && oldState != null && !oldState.isAir() && (newState == null || !oldState.is(newState.getBlock()))) {
            vg$submit(level, pos, oldState);
        }
        return changed;
    }

    private static void vg$submit(Level level, BlockPos pos, BlockState oldState) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            String blockId;
            try {
                ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(oldState.getBlock());
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
