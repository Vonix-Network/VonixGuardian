/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LANE 8 — jukebox record insertion and ejection.
 *
 * <h2>Why this mixin exists</h2>
 * A jukebox holds a music disc in its block entity, not in a {@code Container}.
 * {@code ForgeWorldMutator.tryAddToContainer} / {@code tryRemoveFromContainer}
 * resolve their target through {@code Level#getBlockEntity} and require
 * {@code instanceof Container}, so a jukebox returns {@code false} — neither the
 * container producers nor any existing event covers it. Inserting or ejecting a
 * disc left no row at all.
 *
 * <h2>Two distinct vanilla paths</h2>
 * <ul>
 *   <li><b>Insert</b> — {@code RecordItem.useOn} on a non-playing jukebox calls
 *       {@code JukeboxBlock.setRecord}, sets {@code HAS_RECORD} and shrinks the
 *       held stack. Hooked on {@code RecordItem.useOn}, which is where the player
 *       action originates.</li>
 *   <li><b>Eject</b> — right-clicking a playing jukebox calls
 *       {@code JukeboxBlock.use}, which pops the disc out via the private
 *       {@code dropRecording} and clears the block entity. Hooked on
 *       {@code JukeboxBlock.use}.</li>
 * </ul>
 *
 * <h2>Action type</h2>
 * {@link ActionType#CLICK} (interaction, audit-only). A jukebox is not a
 * {@code Container}, so {@code CONTAINER_DEPOSIT}/{@code CONTAINER_WITHDRAW}
 * would be planned and then fail in the mutator at rollback time — exactly the
 * "rollback cannot fail" violation this release is closing. The disc identity
 * and count are carried in {@code targetId}, with before/after detail in
 * {@code target_meta}.
 */
@Mixin(JukeboxBlock.class)
public abstract class JukeboxBlockMixin {

    /** {@code HAS_RECORD} state at the moment {@code use} was entered. */
    @Unique
    private boolean vg$jukeboxWasPlaying;

    /** Disc in the block entity before {@code use} runs. */
    @Unique
    private ItemStack vg$jukeboxDiscBefore;

    /** Armed only for a successful pre-state capture. */
    @Unique
    private boolean vg$jukeboxArmed;

    /**
     * Capture pre-state for the eject path.
     *
     * <p>{@code use} returns {@code PASS} untouched when the jukebox is empty, so
     * the RETURN handler must be able to tell "nothing happened" from
     * "the disc was popped". Reading {@code HAS_RECORD} and the block entity here
     * gives it both halves.</p>
     */
    @Inject(
            method = "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeJukeboxUse(BlockState state, Level level, BlockPos pos, Player player,
                                     InteractionHand hand, BlockHitResult hit,
                                     CallbackInfoReturnable<InteractionResult> cir) {
        vg$jukeboxArmed = false;
        try {
            if (level == null || pos == null || level.isClientSide || state == null) {
                return;
            }
            vg$jukeboxWasPlaying = state.hasProperty(JukeboxBlock.HAS_RECORD)
                    && Boolean.TRUE.equals(state.getValue(JukeboxBlock.HAS_RECORD));
            BlockEntity be = level.getBlockEntity(pos);
            ItemStack disc = be instanceof JukeboxBlockEntity jb ? jb.getRecord() : ItemStack.EMPTY;
            vg$jukeboxDiscBefore = disc == null ? ItemStack.EMPTY : disc.copy();
            vg$jukeboxArmed = true;
        } catch (Throwable ignored) {
            vg$jukeboxArmed = false;
        }
    }

    /**
     * Emit the eject row if {@code use} actually removed the disc.
     *
     * <p>The test is the real post-state, not the return value: {@code use}
     * returns {@code sidedSuccess} whether or not a record was present, and
     * returns {@code PASS} only for the empty case. Comparing
     * {@code HAS_RECORD} before/after plus the block-entity contents covers both
     * vanilla and modded jukebox subclasses that keep the property but change the
     * storage.</p>
     */
    @Inject(
            method = "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterJukeboxUse(BlockState state, Level level, BlockPos pos, Player player,
                                    InteractionHand hand, BlockHitResult hit,
                                    CallbackInfoReturnable<InteractionResult> cir) {
        if (!vg$jukeboxArmed) {
            return;
        }
        vg$jukeboxArmed = false;
        boolean wasPlaying = vg$jukeboxWasPlaying;
        ItemStack before = vg$jukeboxDiscBefore == null ? ItemStack.EMPTY : vg$jukeboxDiscBefore;
        vg$jukeboxDiscBefore = null;
        try {
            if (player == null || level == null || pos == null) {
                return;
            }
            BlockState now = level.getBlockState(pos);
            boolean playingNow = now != null && now.hasProperty(JukeboxBlock.HAS_RECORD)
                    && Boolean.TRUE.equals(now.getValue(JukeboxBlock.HAS_RECORD));
            BlockEntity be = level.getBlockEntity(pos);
            ItemStack after = be instanceof JukeboxBlockEntity jb ? jb.getRecord() : ItemStack.EMPTY;
            if (after == null) {
                after = ItemStack.EMPTY;
            }
            // Eject only: the jukebox stopped and the disc that was there is gone.
            boolean ejected = wasPlaying && !playingNow && !before.isEmpty()
                    && !ItemStack.isSameItemSameTags(before, after);
            if (!ejected) {
                return;
            }
            vg$emit(player, level, pos, ForgeMixinBridge.itemId(before),
                    "{\"action\":\"eject\",\"disc\":\"" + ForgeMixinBridge.itemId(before)
                            + "\",\"count\":" + before.getCount() + "}");
        } catch (Throwable ignored) {
            // A mixin must never throw into the server thread.
        }
    }

    /**
     * Submit an audit-only row for the given jukebox position.
     *
     * <p>Kept private to this mixin: the insert path lives in its own mixin
     * ({@link JukeboxRecordItemMixin}) and carries its own copy, because
     * {@code @Unique} members are per-class and must not be shared.</p>
     */
    @Unique
    private static void vg$emit(Player player, Level level, BlockPos pos,
                                String targetId, String meta) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null || level == null || pos == null) {
                return;
            }
            g.submitAccepted(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player == null ? null : player.getUUID())
                    .actorName(player == null ? null : player.getName().getString())
                    .worldId(level.dimension().location().toString())
                    .position(pos.getX(), pos.getY(), pos.getZ())
                    .targetId(targetId == null ? "minecraft:air" : targetId)
                    .targetMeta(meta)
                    .amount(1)
                    .sourceTag("#interact:jukebox")
                    .build());
        } catch (Throwable ignored) {
            // defensive: never escape into the server thread
        }
    }
}
