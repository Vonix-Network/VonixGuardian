/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures vanilla leaves natural decay — v1.3.0 W1c:
 * only submit when the tick actually removes the block, not on every tick head.
 * Prior HEAD-injection fired for every leaves tick even when persistent/near-log short-circuits
 * ran or the decay branch never actually deleted anything; @Redirect on
 * {@code ServerLevel.removeBlock} guarantees we only submit on real mutations.
 */
@Mixin(LeavesBlock.class)
public abstract class LeavesBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$leavesRemove(ServerLevel level, BlockPos pos, boolean moving) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.removeBlock(pos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            try {
                NeoForgeMixinBridge.leavesDecay(level, pos, oldState);
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }
}
