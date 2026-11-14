/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import network.vonix.guardian.core.event.EventSubmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinAuditSupport;

/**
 * Lectern "book taken" producer — the block-entity half.
 *
 * <p>Taking the book out of a lectern moves it into the player's inventory (or
 * onto the ground when the inventory is full). Forge fires no event for it and the
 * block does not otherwise change state, so today the removal of a written book
 * leaves no row anywhere — the most common lectern grief on a public server, and
 * the one an operator most needs attributed.</p>
 *
 * <h2>Why {@code onBookItemRemove}</h2>
 *
 * <p>{@code LecternBlockEntity} is not a {@code Container}; it wraps a private
 * one-slot adapter. A hopper therefore cannot extract a lectern's book at all,
 * because {@code HopperBlockEntity} only moves items into and out of
 * {@code Container}s — so the GUI take button is the real player-facing surface,
 * and automation is not a competing source of removals to disambiguate.</p>
 *
 * <p>Every path that actually empties the lectern funnels through
 * {@code onBookItemRemove()}: the adapter calls it from both
 * {@code removeItemNoUpdate(int)} (the take button) and
 * {@code removeItem(int, int)} (the generic contract). It runs <em>after</em> the
 * book has been detached, so the item identity cannot be read here — the held
 * stack is captured by {@link LecternMenuTakeMixin} at the button press, which
 * runs strictly before this. What this hook uniquely provides is the
 * <strong>position</strong>: the block entity knows its own coordinates, and the
 * menu does not.</p>
 *
 * <p>Emitting here rather than in the adapter also avoids mixing into an
 * anonymous inner class, whose synthetic back-reference field is spelled
 * differently in source ({@code this$0}) and under SRG ({@code f_59572_}).
 * Targeting a public, stably-named class keeps {@code @Shadow} out of the mixin
 * entirely.</p>
 *
 * <h2>Row shape</h2>
 *
 * <p>{@link network.vonix.guardian.core.action.ActionType#CONTAINER_WITHDRAW} at
 * the lectern's coordinates with the book's registry id, the number of items
 * taken, and item NBT. Its inverse is {@code tryGiveOrDrop} at that position,
 * which returns the book to the world — the same best-effort restore every
 * container withdrawal already receives when the target cannot accept the item
 * back. The row is reversible at plan time and cannot produce a mid-run
 * failure.</p>
 *
 * <p><b>Placing a book is deliberately not logged.</b> The inverse of
 * {@code CONTAINER_DEPOSIT} is {@code tryRemoveFromContainer}, which requires the
 * block entity to be a {@code Container}; a lectern's is not one. A deposit row
 * would be an action the rollback engine is guaranteed to fail on, so emitting one
 * would be strictly worse than emitting none.</p>
 */
@Mixin(LecternBlockEntity.class)
public abstract class LecternBookMixin {

    /**
     * Fires when the lectern's book slot empties.
     *
     * <p>The book has already been detached, so the item is read from the capture
     * {@link LecternMenuTakeMixin} pushed. If nothing was captured — a removal
     * that bypassed the menu — no row is emitted for that event rather than a row
     * with an unknown item, because a withdrawal row with a null {@code targetId}
     * would be unreadable in lookup and unreversible in rollback.</p>
     */
    @Inject(method = "m_59570_()V",
            at = @At("HEAD"),
            require = 1,
            remap = false)
    private void vg$onBookRemoved(CallbackInfo ci) {
        try {
            LecternBlockEntity self = (LecternBlockEntity) (Object) this;
            Level level = self.getLevel();
            BlockPos pos = self.getBlockPos();
            if (level == null || level.isClientSide || pos == null) return;

            MixinAuditSupport.LecternTake take = MixinAuditSupport.peekLecternTake(pos);
            if (take == null) return;
            ItemStack book = take.book();
            if (book == null || book.isEmpty()) return;

            EventSubmitter s = MixinAuditSupport.submitter();
            if (s == null) return;
            String itemId = MixinAuditSupport.itemId(book);
            if (itemId == null) return;

            s.submitContainerChange(
                    take.actor() == null ? null : take.actor().getUUID(),
                    take.actor() == null ? "#lectern" : take.actor().getName().getString(),
                    MixinAuditSupport.worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId, -Math.max(1, book.getCount()), "#interact:lectern_take",
                    MixinAuditSupport.itemNbt(book));
        } catch (Throwable ignored) {
        }
    }
}
