/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: EnderDragon.checkWalls -> removeBlock. */
@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Redirect(method = "checkWalls", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$dragonRemoveBlock(ServerLevel level, BlockPos pos, boolean isMoving) {
        BlockState oldState = level.getBlockState(pos);
        boolean removed = level.removeBlock(pos, isMoving);
        if (removed && oldState != null && !oldState.isAir()) {
            EnderDragon self = (EnderDragon) (Object) this;
            FabricMixinBridge.entityBreak(self, level, pos, oldState, EntitySentinel.SRC_ENDER_DRAGON);
        }
        return removed;
    }
}
