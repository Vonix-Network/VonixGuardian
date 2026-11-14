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
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LANE 8 — note block tuning.
 *
 * <h2>Why this mixin exists</h2>
 * Right-clicking a note block increments its {@code NOTE} property (0–24). That
 * is a persistent edit to a placed block: it changes an instrument's pitch for
 * every later player. Nothing recorded it, so a build's redstone tune could be
 * retuned with no trace.
 *
 * <h2>Scope: tuning only, not playback</h2>
 * {@code NoteBlock.attack} also produces a note (a left-click plays the sound
 * without changing state). Logging that would flood the queue with an audit row
 * for every hit on a note block while recording <em>no</em> state change, so it is
 * intentionally out of scope. Only an actual {@code NOTE} change emits a row.
 *
 * <h2>Action type</h2>
 * {@link ActionType#CLICK} (interaction, audit-only) rather than
 * {@code BLOCK_PLACE}: the block was not replaced, and a
 * {@code BLOCK_PLACE} row would make a rollback clear the note block entirely —
 * destroying far more than the player changed. The old and new pitch are carried
 * in {@code target_meta} so an operator can see exactly what moved.
 */
@Mixin(NoteBlock.class)
public abstract class NoteBlockMixin {

    /** {@code NOTE} value when {@code use} was entered. */
    @Unique
    private int vg$noteBefore;

    /** Armed only when the pre-state was read successfully. */
    @Unique
    private boolean vg$noteArmed;

    /** Capture the pitch before the interaction mutates it. */
    @Inject(
            method = "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeNoteUse(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit,
                                  CallbackInfoReturnable<InteractionResult> cir) {
        vg$noteArmed = false;
        try {
            if (state == null || !state.hasProperty(NoteBlock.NOTE)) {
                return;
            }
            vg$noteBefore = state.getValue(NoteBlock.NOTE);
            vg$noteArmed = true;
        } catch (Throwable ignored) {
            vg$noteArmed = false;
        }
    }

    /**
     * Emit a row when the pitch really changed.
     *
     * <p>{@code use} returns {@code SUCCESS} client-side, {@code FAIL} when Forge
     * vetoes the change ({@code ForgeHooks.onNoteChange}), and {@code CONSUME}
     * otherwise — so the return value alone is not evidence. The post-state is.</p>
     */
    @Inject(
            method = "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterNoteUse(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit,
                                 CallbackInfoReturnable<InteractionResult> cir) {
        if (!vg$noteArmed) {
            return;
        }
        vg$noteArmed = false;
        int before = vg$noteBefore;
        try {
            if (level == null || pos == null || level.isClientSide) {
                return;
            }
            BlockState now = level.getBlockState(pos);
            if (now == null || !now.hasProperty(NoteBlock.NOTE)) {
                return;
            }
            int after = now.getValue(NoteBlock.NOTE);
            if (after == before) {
                return;
            }
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) {
                return;
            }
            g.submitAccepted(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player == null ? null : player.getUUID())
                    .actorName(player == null ? null : player.getName().getString())
                    .worldId(level.dimension().location().toString())
                    .position(pos.getX(), pos.getY(), pos.getZ())
                    .targetId("minecraft:note_block")
                    .targetMeta("{\"note\":" + before + "->" + after
                            + ",\"instrument\":\"" + vg$instrument(now) + "\"}")
                    .amount(1)
                    .sourceTag("#interact:note_block")
                    .build());
        } catch (Throwable ignored) {
            // A mixin must never throw into the server thread.
        }
    }

    /** Instrument name for the row detail; empty when the property is absent. */
    @Unique
    private static String vg$instrument(BlockState state) {
        try {
            return state.hasProperty(NoteBlock.INSTRUMENT)
                    ? state.getValue(NoteBlock.INSTRUMENT).getSerializedName()
                    : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
