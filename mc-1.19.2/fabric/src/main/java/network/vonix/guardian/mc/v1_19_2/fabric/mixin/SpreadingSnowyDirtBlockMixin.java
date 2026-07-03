/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Captures actual grass/mycelium spread mutations on Fabric. */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtBlockMixin {

    @Redirect(method = "randomTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean vg$spreadSet(ServerLevel level, BlockPos pos, BlockState newState) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlockAndUpdate(pos, newState);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                FabricMixinBridge.blockFade(level, pos, oldState);
            } else {
                FabricMixinBridge.blockSpread(level, pos, newState);
            }
        }
        return changed;
    }
}
