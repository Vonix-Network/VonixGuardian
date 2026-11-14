/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WetSpongeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wet sponge drying — in an ultra-warm dimension {@code WetSpongeBlock#onPlace}
 * replaces itself with a dry sponge.
 *
 * <p>Captured at {@code RETURN} by re-reading the position, so the row appears
 * only when vanilla actually swapped the block. Emitted as
 * {@link network.vonix.guardian.core.action.ActionType#FADE} carrying the
 * <em>pre</em>-change id ({@code minecraft:wet_sponge}); FADE's inverse restores
 * that exact block, so a rollback re-wets the sponge.</p>
 */
@Mixin(WetSpongeBlock.class)
public abstract class WetSpongeDryMixin {

    /**
     * @param state    unused
     * @param level    world the sponge was placed in
     * @param pos      the sponge's own block position
     * @param oldState unused
     * @param moving   unused
     * @param cir      unused
     */
    @Inject(
            method = "m_6807_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterWetSpongePlace(BlockState state, Level level, BlockPos pos,
                                        BlockState oldState, boolean moving, CallbackInfo ci) {
        try {
            if (level == null || pos == null || level.isClientSide) return;
            BlockState after = level.getBlockState(pos);
            if (after == null || !after.is(Blocks.SPONGE)) return;
            MixinLoggingSupport.emitFade(level, pos, Blocks.WET_SPONGE.defaultBlockState(), after, "#sponge:dry");
        } catch (Throwable ignored) {
        }
    }
}
