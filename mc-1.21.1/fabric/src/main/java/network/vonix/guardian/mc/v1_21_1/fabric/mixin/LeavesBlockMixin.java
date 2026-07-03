/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Captures actual natural leaf decay removals on Fabric. */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$leavesRemove(ServerLevel level, BlockPos pos, boolean moving) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.removeBlock(pos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            FabricMixinBridge.leavesDecay(level, pos, oldState);
        }
        return changed;
    }
}
