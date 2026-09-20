/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_3.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v26_3.common.EntitySentinel;
import network.vonix.guardian.mc.v26_3.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X2 — Ledger-parity: {@link FallingBlockEntity} fall (break) + land (place).
 *
 * <p>Ledger installs two hooks
 * ({@code entities/FallingBlockEntityMixin.java:22-42}):</p>
 * <ol>
 *   <li>{@code fall(Level, BlockPos, BlockState)} — before the source block is
 *       replaced with air, capture a BREAK at the source.</li>
 *   <li>{@code tick} — before {@code Level.setBlockAndUpdate(pos, state)} places
 *       the falling block at its destination, capture a PLACE at the target.</li>
 * </ol>
 *
 * <p>Both are tagged with {@link EntitySentinel#SRC_GRAVITY}, matching Ledger's
 * {@code Sources.GRAVITY}. This lets operators see both halves of a sand /
 * gravel / anvil trail in {@code /vg inspect} instead of one half being fired
 * by the aggregate {@code EntityChangeBlockEvent} (Forge/NeoForge only) and
 * the other going unlogged entirely on Fabric.</p>
 *
 * <p>NOTE: {@code fall} is static in vanilla, so its redirect handler is
 * static too. {@code tick} is instance-scoped — we look up {@code this}
 * (via the {@code @Shadow} field {@code blockState}) to attach the actor.</p>
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Shadow
    private BlockState blockState;

    // 26.3: both fall() and tick() place via setBlockAndUpdate, not
    // Level.setBlock(pos, state, flags).
    @Redirect(
            method = "fall",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private static boolean vg$fallingBlockFallSetBlock(Level level, BlockPos pos, BlockState newState) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlockAndUpdate(pos, newState);
        if (changed && oldState != null && !oldState.isAir()) {
            // Source of the fall — no entity yet, so use the sentinel string directly.
            NeoForgeMixinBridge.entityBreak(null, level, pos, oldState, EntitySentinel.SRC_GRAVITY);
        }
        return changed;
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean vg$fallingBlockLandSetBlock(Level level, BlockPos pos, BlockState placedState) {
        boolean placed = level.setBlockAndUpdate(pos, placedState);
        if (placed && placedState != null && !placedState.isAir()) {
            FallingBlockEntity self = (FallingBlockEntity) (Object) this;
            NeoForgeMixinBridge.entityPlace(self, level, pos, placedState, EntitySentinel.SRC_GRAVITY);
        }
        return placed;
    }
}
