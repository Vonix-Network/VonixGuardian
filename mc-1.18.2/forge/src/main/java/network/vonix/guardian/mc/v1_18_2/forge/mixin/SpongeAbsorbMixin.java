/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpongeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinLoggingSupport;

/**
 * Sponge absorption — {@code SpongeBlock#tryAbsorbWater} drains a connected water
 * body and converts the sponge itself into a wet sponge.
 *
 * <p>Colour is captured at {@code RETURN} by re-reading the block at the sponge's
 * own position, so a row is emitted only when vanilla really performed the
 * conversion. Emitted as
 * {@link network.vonix.guardian.core.action.ActionType#FADE} carrying the
 * <em>pre</em>-absorption id ({@code minecraft:sponge}); FADE's inverse restores
 * exactly that block, so rollback puts a dry sponge back.</p>
 *
 * <p><b>Stated limit, not a silent gap:</b> the drained water cells are not
 * individually recorded. Vanilla clamps one absorption at 64 cells and reaches
 * them through two different {@code Level#setBlock} call sites inside a private
 * method, so a per-cell row would pin our injector to a private mapping detail.
 * The sponge conversion is the durable record an operator looks for; the water is
 * recoverable by hand. This is reported in LANE-10.md rather than hidden inside a
 * rollback counter.</p>
 */
@Mixin(SpongeBlock.class)
public abstract class SpongeAbsorbMixin {

    /**
     * @param level world the sponge ticked in
     * @param pos   the sponge's own block position
     * @param cir   unused
     */
    @Inject(
            method = "m_56797_(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterAbsorbWater(Level level, BlockPos pos, CallbackInfo cir) {
        try {
            if (level == null || pos == null || level.isClientSide) return;
            BlockState after = level.getBlockState(pos);
            if (after == null || !after.is(Blocks.WET_SPONGE)) return;
            MixinLoggingSupport.emitFade(level, pos, Blocks.SPONGE.defaultBlockState(), after, "#sponge:absorb");
        } catch (Throwable ignored) {
        }
    }
}
