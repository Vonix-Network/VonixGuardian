/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinLoggingSupport;

/**
 * Chorus flower growth — {@code ChorusFlowerBlock#randomTick} performs one of three
 * mutations, all unlogged before this mixin:
 *
 * <ol>
 *   <li><b>vertical</b> — plants a {@code chorus_plant} at {@code pos.above()},
 *       then replaces it with a fresh {@code chorus_flower};</li>
 *   <li><b>branch</b> — places a {@code chorus_flower} sideways and converts
 *       {@code pos} itself into {@code chorus_plant};</li>
 *   <li><b>die</b> — converts {@code pos} into a fully grown {@code chorus_flower}
 *       with {@code age = 5}.</li>
 * </ol>
 *
 * <p>Both cells are snapshotted at {@code HEAD} and re-read at {@code RETURN}. A
 * change at {@code pos.above()} is a block that appeared on its own →
 * {@code STRUCTURE_GROW} (inverse: clear the cell). A change at {@code pos} is this
 * flower converting away → {@code FADE} carrying the pre-change id (inverse:
 * restore it). Re-reading rather than trusting vanilla's branch structure makes
 * both rows verifiable rather than assumed, and it means the same two injectors
 * cover all three branches without depending on a private helper's call count.</p>
 *
 * <p><b>Scope limit, stated:</b> a branch that lands two or three cells sideways is
 * not enumerated as a separate growth row — only the {@code pos} conversion into
 * {@code chorus_plant} is reported for that branch. Wrapping the private
 * {@code placeGrownFlower} / {@code placeDeadFlower} helpers would report each one
 * but pins the injector to private mapping details that Forge does not guarantee
 * across 1.18.2 patch releases. A chorus patch grown while no operator was looking
 * therefore shows its branch origins, not every branch tip; this is recorded in
 * LANE-10.md rather than left implicit.</p>
 */
@Mixin(ChorusFlowerBlock.class)
public abstract class ChorusGrowthMixin {

    @Inject(
            method = "m_7455_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeChorusTick(BlockState state, ServerLevel level, BlockPos pos,
                                     Random random, CallbackInfo ci) {
        // Push order matters: drainWatches pops LIFO, so seed the "above" growth
        // watch first and the "self" fade watch second to emit fade before growth.
        MixinLoggingSupport.pushWatch(MixinLoggingSupport.CHORUS_WATCHES, level, pos == null ? null : pos.above(), false);
        MixinLoggingSupport.pushWatch(MixinLoggingSupport.CHORUS_WATCHES, level, pos, true);
    }

    @Inject(
            method = "m_7455_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/Random;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterChorusTick(BlockState state, ServerLevel level, BlockPos pos,
                                    Random random, CallbackInfo ci) {
        MixinLoggingSupport.drainWatches(MixinLoggingSupport.CHORUS_WATCHES, level, 2, "#natural:chorus");
    }
}
