/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import network.vonix.guardian.core.event.EventSubmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinAuditSupport;

/**
 * Bed producers — the two removals vanilla performs with no break event.
 *
 * <h2>1. The orphaned half</h2>
 *
 * <p>A bed is two blocks. Breaking one half makes vanilla delete the other from
 * {@code updateShape} with a plain {@code Level#setBlock}; Forge fires
 * {@code BlockEvent.BreakEvent} only for the half the player actually hit. The
 * measured consequence is a half-restored bed: a rollback replays the one logged
 * {@code BLOCK_BREAK}, the unlogged half stays gone, vanilla's next neighbour
 * update then deletes the restored half too, and the operator's {@code affected=}
 * count claims a success the world contradicts. Logging the partner closes that
 * hole: two blocks broken produce two rows.</p>
 *
 * <p><b>Why the partner and not the hit half.</b> The hit half is already logged
 * by {@code ForgeEvents.onBlockBreak} from {@code BlockEvent.BreakEvent}, which
 * fires before {@code playerWillDestroy}. Emitting a row for {@code pos} here
 * would duplicate that row and force the rollback planner to dedupe two rows for
 * one block. This hook therefore records only the half that has no other row.
 * Exactly two rows per bed break, one per block, never more.</p>
 *
 * <p>The partner is still attached at {@code HEAD}, so both its identity and its
 * exact {@code part} / {@code facing} / {@code occupied} triple are captured
 * before vanilla cascades the removal. The {@link #vg$submitBreak} row carries
 * that state as {@code oldBlockState}, which is what {@code trySetBlock} replays
 * on rollback — without it the partner would restore as the block default and
 * the pair would collapse again.</p>
 *
 * <h2>2. Bed explosion in a dimension where beds do not work</h2>
 *
 * <p>Using a bed where {@code BedBlock.canSetSpawn} is false calls
 * {@code removeBlock} on both halves directly — not through the game mode, so
 * again no break event for either. The blast's wider damage is carried by the
 * {@code EXPLOSION} row; the two bed blocks the player's own click destroyed were
 * not recorded at all. Both are logged here, before the removal.</p>
 *
 * <p>Rows are {@link network.vonix.guardian.core.action.ActionType#BLOCK_BREAK}
 * because that is the type whose inverse ({@code trySetBlock} with the captured
 * state) restores a bed. Emitting {@code CLICK} would record the event and still
 * be unable to restore it, since {@code CLICK} is refused by both
 * {@code applyInverse} and {@code applyForward}.</p>
 *
 * <p><b>Binding.</b> {@code playerWillDestroy} is SRG
 * {@code m_5707_(Level, BlockPos, BlockState, Player)V} and {@code use} is SRG
 * {@code m_6227_(BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)InteractionResult}.
 * Both bind with {@code remap = false} and {@code require = 1}, as everywhere else
 * in this cell: a mapping change must fail at apply time, not silently stop
 * logging.</p>
 */
@Mixin(BedBlock.class)
public abstract class BedBlockMixin {

    /**
     * Records the opposite half of a bed before vanilla's cascade deletes it.
     *
     * <p>Runs for the half the player is destroying; the partner is read before
     * any removal, and the guard on block identity makes a second call for an
     * already-vanished half a no-op. Creative-mode foot breaks remove the head
     * inside this same method, which is after {@code HEAD}, so the head is
     * captured while still present.</p>
     */
    @Inject(
            method = "m_5707_(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$onWillDestroy(Level level, BlockPos pos, BlockState state, Player player,
                                  CallbackInfo ci) {
        try {
            if (level == null || level.isClientSide || pos == null || state == null || player == null) return;
            BlockPos partner = vg$partnerPos(state, pos);
            if (partner == null) return;
            BlockState partnerState = level.getBlockState(partner);
            if (partnerState == null || partnerState.getBlock() != state.getBlock()) return;
            vg$submitBreak(level, partner, partnerState, player, "#interact:bed_partner");
        } catch (Throwable ignored) {
        }
    }

    /**
     * Records both halves of a bed that is about to explode because it was used
     * in a dimension where beds do not work.
     *
     * <p>Only the non-working-bed path is logged. A normal sleep changes no block,
     * so the {@code canSetSpawn} guard keeps every ordinary bedtime interaction
     * out of the audit log.</p>
     */
    @Inject(
            method = "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$onUse(BlockState state, Level level, BlockPos pos, Player player,
                          InteractionHand hand, BlockHitResult hit,
                          CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (level == null || level.isClientSide || pos == null || state == null || player == null) return;
            if (BedBlock.canSetSpawn(level)) return;
            vg$submitBreak(level, pos, state, player, "#interact:bed_explode");
            BlockPos partner = vg$partnerPos(state, pos);
            if (partner == null) return;
            BlockState partnerState = level.getBlockState(partner);
            if (partnerState == null || partnerState.getBlock() != state.getBlock()) return;
            vg$submitBreak(level, partner, partnerState, player, "#interact:bed_explode");
        } catch (Throwable ignored) {
        }
    }

    /**
     * @return the position of the other half, or {@code null} when the state does
     *         not carry the bed properties — a modded block extending
     *         {@code BedBlock} without {@code PART}/{@code FACING}
     */
    private static BlockPos vg$partnerPos(BlockState state, BlockPos pos) {
        try {
            if (!state.hasProperty(BedBlock.PART) || !state.hasProperty(BedBlock.FACING)) return null;
            return pos.relative(BedBlock.getConnectedDirection(state));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void vg$submitBreak(Level level, BlockPos pos, BlockState state,
                                       Player player, String sourceTag) {
        try {
            EventSubmitter s = MixinAuditSupport.submitter();
            if (s == null) return;
            String blockId = MixinAuditSupport.blockId(state);
            if (blockId == null) return;
            s.submitBlockBreak(player.getUUID(), player.getName().getString(),
                    MixinAuditSupport.worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId, sourceTag,
                    MixinAuditSupport.stateProps(state), null);
        } catch (Throwable ignored) {
        }
    }
}
