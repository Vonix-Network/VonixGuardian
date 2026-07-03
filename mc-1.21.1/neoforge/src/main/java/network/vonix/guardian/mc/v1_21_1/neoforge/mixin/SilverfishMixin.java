/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X2 — Ledger-parity: Silverfish infest goal (stone → infested_stone).
 *
 * <p>Ledger installs its silverfish coverage inside two nested goals in the
 * {@code Silverfish} class:</p>
 * <ul>
 *   <li>{@code Silverfish$SilverfishWakeUpFriendsGoal} — captures the call-for-help
 *       {@code destroyBlock} that breaks the infested surround. Ledger uses
 *       {@code @WrapOperation} ({@code silverfish/CallForHelpGoalMixin.java}).</li>
 *   <li>{@code Silverfish$SilverfishMergeWithStoneGoal} — captures the infest
 *       via {@code LevelAccessor.setBlock(pos, state, flags)}
 *       ({@code silverfish/WanderAndInfestGoalMixin.java}).</li>
 * </ul>
 *
 * <p>We ship the {@code MergeWithStone} redirect here — the higher-impact of
 * the two, since infest events are a distinct block-state change (stone → infested_*)
 * that the aggregate {@code LivingDestroyBlockEvent} entirely misses (it's a
 * setBlock, not destroy). The call-for-help path IS covered by the existing
 * aggregate {@code LivingDestroyBlockEvent} handler on Forge/NeoForge and by
 * {@code LivingDestroyBlockMixin} on Fabric, so we don't need a second target
 * to reach parity.</p>
 *
 * <p>Target uses a string-form {@code targets = ...} because the goal is an
 * inner class not accessible via normal {@code @Mixin(Class)} on 1.21.1.
 * Extends {@code RandomStrollGoal} because the vanilla goal does — otherwise
 * mixinextras rejects the extends chain during apply.</p>
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishMergeWithStoneGoal")
public abstract class SilverfishMixin extends RandomStrollGoal {

    public SilverfishMixin(PathfinderMob mob, double speed) {
        super(mob, speed);
    }

    @Redirect(
            method = "start",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            )
    )
    private boolean vg$silverfishInfest(LevelAccessor access, BlockPos pos, BlockState newState, int flags) {
        BlockState oldState = access.getBlockState(pos);
        boolean placed = access.setBlock(pos, newState, flags);
        if (placed && newState != null && oldState != null) {
            NeoForgeMixinBridge.entityChange(this.mob, this.mob.level(), pos, oldState, newState, EntitySentinel.SRC_SILVERFISH);
        }
        return placed;
    }
}
