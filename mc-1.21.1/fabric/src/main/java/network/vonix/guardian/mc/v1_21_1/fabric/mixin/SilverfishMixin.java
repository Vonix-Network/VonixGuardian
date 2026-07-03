/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: Silverfish infest (stone -> infested_stone). */
@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal")
public abstract class SilverfishMixin extends RandomStrollGoal {
    public SilverfishMixin(PathfinderMob mob, double speed) { super(mob, speed); }

    @Redirect(method = "start", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$silverfishInfest(LevelAccessor access, BlockPos pos, BlockState newState, int flags) {
        BlockState oldState = access.getBlockState(pos);
        boolean placed = access.setBlock(pos, newState, flags);
        if (placed && newState != null && oldState != null) {
            FabricMixinBridge.entityChange(this.mob, this.mob.level(), pos, oldState, newState, EntitySentinel.SRC_SILVERFISH);
        }
        return placed;
    }
}
