/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X4 — Nether-portal frame creation producer.
 *
 * <p>Ledger's {@code PortalShapeMixin} injects into the lambda inside
 * {@code createPortalBlocks} at the point of {@code LevelAccessor.setBlock}.
 * We do the same but via {@link Redirect} so we observe the actual write
 * (skipping cancelled writes) rather than a HEAD/RETURN broad-brush inject.
 * {@code require = 0} keeps us boot-safe on mapping variants where the
 * synthetic lambda name differs.</p>
 *
 * <p>Attribution: {@link network.vonix.guardian.core.event.Sentinel#PORTAL}
 * (no player context inside {@code createPortalBlocks}).</p>
 */
@Mixin(PortalShape.class)
public abstract class PortalShapeMixin {

    @Redirect(
        method = "createPortalBlocks",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        require = 0)
    private boolean vg$onPortalSetBlock(LevelAccessor accessor, BlockPos pos, BlockState state, int flags) {
        boolean changed = accessor.setBlock(pos, state, flags);
        if (changed && accessor instanceof Level level) {
            try {
                FabricMixinBridge.portalCreate(level, pos.immutable(), state);
            } catch (Throwable ignored) {}
        }
        return changed;
    }
}
