/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: LightningBolt.spawnFire ignite (impact + spread). */
@Mixin(LightningBolt.class)
public abstract class LightningBoltMixin {
    @Redirect(method = "spawnFire", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean vg$lightningFireSpawn(ServerLevel level, BlockPos pos, BlockState newState) {
        boolean placed = level.setBlockAndUpdate(pos, newState);
        if (placed && newState != null && !newState.isAir()) {
            LightningBolt self = (LightningBolt) (Object) this;
            FabricMixinBridge.entityPlace(self, level, pos, newState, EntitySentinel.SRC_LIGHTNING);
        }
        return placed;
    }
}
