/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures grass/mycelium/podzol spread events on Forge.
 *
 * <p>v1.3.0 W1b: switched from HEAD @Inject on {@code randomTick} to a
 * targeted @Redirect on {@code ServerLevel#setBlockAndUpdate}. Under the old
 * HEAD hook every random-tick of a grass/mycelium block submitted a SPREAD
 * action even when no dirt neighbor was actually converted; the redirect only
 * fires when the vanilla code path calls setBlockAndUpdate.</p>
 */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {

    @Redirect(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean vg$spreadSet(ServerLevel level, BlockPos pos, BlockState newState) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlockAndUpdate(pos, newState);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            try {
                Guardian g = VonixGuardianForge.guardian();
                if (g == null) return changed;
                EventSubmitter s = g.submitter();
                if (s == null) return changed;
                s.submitSpread(null, "#natural",
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        vg$blockId(newState), "world:spread");
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    private static String vg$blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:grass_block";
        } catch (Throwable t) {
            return "minecraft:grass_block";
        }
    }
}
