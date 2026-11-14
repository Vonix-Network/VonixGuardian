/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * LANE 8 — music disc insertion into a jukebox.
 *
 * <h2>Why a separate mixin</h2>
 * Insertion does not run through {@code JukeboxBlock}: the disc item owns the
 * interaction. {@code RecordItem.useOn} checks the clicked block is a jukebox
 * with {@code HAS_RECORD == false}, calls {@code JukeboxBlock.setRecord}, flips
 * the block state and shrinks the held stack. A mixin targeting
 * {@code JukeboxBlock} therefore cannot see it, and {@code @Mixin} may only
 * target one class — so the insert half lives here.
 *
 * <p>{@code JukeboxBlock.setRecord} is deliberately <em>not</em> the hook: it is
 * also called from {@code JukeboxBlockEntity.load}, so a chunk reload would
 * replay a row for every jukebox in the chunk.</p>
 *
 * <h2>Action type</h2>
 * {@link ActionType#CLICK} (interaction, audit-only). A jukebox is not a
 * {@code Container}, so the container mutators reject it; emitting
 * {@code CONTAINER_DEPOSIT} would produce a row whose rollback is guaranteed to
 * fail at dispatch.
 */
@Mixin(RecordItem.class)
public abstract class JukeboxRecordItemMixin {

    /**
     * Emit an insert row when {@code useOn} actually started the record.
     *
     * <p>The post-state is the evidence, not the return value alone: a
     * {@code consumesAction} result plus {@code HAS_RECORD == true} and a
     * non-empty record in the block entity can only mean this call inserted the
     * disc.</p>
     */
    @Inject(
            method = "m_6225_(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterRecordUseOn(UseOnContext ctx,
                                     CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (ctx == null || cir == null) {
                return;
            }
            InteractionResult result = cir.getReturnValue();
            if (result == null || !result.consumesAction()) {
                return;
            }
            Level level = ctx.getLevel();
            BlockPos pos = ctx.getClickedPos();
            if (level == null || pos == null || level.isClientSide) {
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (state == null || !state.hasProperty(JukeboxBlock.HAS_RECORD)
                    || !Boolean.TRUE.equals(state.getValue(JukeboxBlock.HAS_RECORD))) {
                return;
            }
            BlockEntity be = level.getBlockEntity(pos);
            ItemStack disc = be instanceof JukeboxBlockEntity jb ? jb.getRecord() : ItemStack.EMPTY;
            if (disc == null || disc.isEmpty()) {
                return;
            }
            String discId = ForgeMixinBridge.itemId(disc);
            Player player = ctx.getPlayer();
            vg$emit(player, level, pos, discId,
                    "{\"action\":\"insert\",\"disc\":\"" + discId + "\",\"count\":"
                            + disc.getCount() + '}');
        } catch (Throwable ignored) {
            // A mixin must never throw into the server thread.
        }
    }

    /** Submit an audit-only row. Never throws. */
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
