/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_3.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v26_3.common.EntitySentinel;
import network.vonix.guardian.mc.v26_3.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X2 — Ledger-parity: {@link Ravager} crop / leaf destruction.
 *
 * <p>Ravagers call {@code Level.destroyBlock(pos, true, entity)} inside
 * {@code aiStep} to break leaves and crops in their path. Ledger uses
 * {@code @ModifyArgs} to intercept those calls
 * (see {@code entities/RavagerMixin.java:14-19}). We use {@code @Redirect}
 * for the same shape but only submit when {@code destroyBlock} returned
 * {@code true}, which avoids the false-positive on air / already-broken tiles.
 * The block state is snapshotted BEFORE the call so we log the actual crop /
 * leaf that was standing there.</p>
 */
@Mixin(Ravager.class)
public abstract class RavagerMixin {

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"
            )
    )
    private boolean vg$ravagerDestroyBlock(ServerLevel level, BlockPos pos, boolean drop, Entity source) {
        BlockState oldState = level.getBlockState(pos);
        boolean destroyed = level.destroyBlock(pos, drop, source);
        if (destroyed && oldState != null && !oldState.isAir()) {
            Ravager self = (Ravager) (Object) this;
            NeoForgeMixinBridge.entityBreak(self, level, pos, oldState, EntitySentinel.SRC_RAVAGER);
        }
        return destroyed;
    }
}
