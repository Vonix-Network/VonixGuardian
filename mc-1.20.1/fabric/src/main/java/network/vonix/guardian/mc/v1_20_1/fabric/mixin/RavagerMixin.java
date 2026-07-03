/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_20_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: Ravager.aiStep -> destroyBlock. */
@Mixin(Ravager.class)
public abstract class RavagerMixin {
    @Redirect(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
    private boolean vg$ravagerDestroyBlock(Level level, BlockPos pos, boolean drop, Entity source) {
        BlockState oldState = level.getBlockState(pos);
        boolean destroyed = level.destroyBlock(pos, drop, source);
        if (destroyed && oldState != null && !oldState.isAir()) {
            Ravager self = (Ravager) (Object) this;
            FabricMixinBridge.entityBreak(self, level, pos, oldState, EntitySentinel.SRC_RAVAGER);
        }
        return destroyed;
    }
}
