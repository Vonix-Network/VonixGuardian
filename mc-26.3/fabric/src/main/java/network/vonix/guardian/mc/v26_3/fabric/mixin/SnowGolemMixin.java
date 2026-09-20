/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_3.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v26_3.common.EntitySentinel;
import network.vonix.guardian.mc.v26_3.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X2 — Ledger-parity: {@link SnowGolem} snow-trail placement.
 *
 * <p>SnowGolems tick a per-column snow layer via
 * {@code Level.setBlockAndUpdate(pos, snow)} inside {@code aiStep}. Ledger
 * captures this with an {@code @Inject(AFTER)} on that INVOKE
 * ({@code entities/SnowGolemMixin.java:24-31}) and tags the source with
 * {@code Sources.SNOW_GOLEM}. We match the pattern with {@code @Redirect}
 * so the row is only produced when the setBlockAndUpdate actually changed
 * the world (returns {@code true}).</p>
 *
 * <p>Note: Fabric 26.3 moves {@code SnowGolem} to
 * {@code net.minecraft.world.entity.animal.SnowGolem} (previously
 * {@code net.minecraft.world.entity.animal.golem.SnowGolem} on some Ledger
 * builds — see MC 26.3 golem package regrouping). We target the current
 * package here.</p>
 */
@Mixin(SnowGolem.class)
public abstract class SnowGolemMixin {

    @Redirect(
            method = "aiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean vg$snowGolemPlace(Level level, BlockPos pos, BlockState state) {
        boolean placed = level.setBlockAndUpdate(pos, state);
        if (placed && state != null) {
            SnowGolem self = (SnowGolem) (Object) this;
            FabricMixinBridge.entityPlace(self, level, pos, state, EntitySentinel.SRC_SNOW_GOLEM);
        }
        return placed;
    }
}
