/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import network.vonix.guardian.core.event.EventSubmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Lectern "book taken" producer — container half.
 *
 * <p>Taking the book out of a lectern moves it into the player's inventory (or
 * onto the ground when the inventory is full). Forge fires no event for it and
 * the block does not otherwise change state, so today the removal of a written
 * book leaves no row anywhere — the most common lectern grief on a public
 * server, and the one an operator most needs attributed.</p>
 *
 * <p><b>Why the anonymous container and not the block entity.</b>
 * {@code LecternBlockEntity} is not a {@code Container}; it exposes a private
 * one-slot {@code Container} adapter ({@code LecternBlockEntity$1}). Vanilla
 * clears the book through exactly two adapter methods —
 * {@code removeItemNoUpdate(int)} for the GUI "take book" button and
 * {@code removeItem(int, int)} for the generic {@code Container} contract — and
 * both null the book out <em>before</em> returning it. By the time
 * {@code onBookItemRemove()} runs the stack is already gone, so the item identity
 * and its NBT can only be read at HEAD of the adapter's removal, while the book
 * is still attached.</p>
 *
 * <p><b>No {@code @Shadow}.</b> The field that points back at the lectern is
 * synthetic ({@code this$0} in source, {@code f_59572_} under SRG). A
 * {@code @Shadow} on either spelling fails hard at apply time under the other
 * naming, which would take the whole cell's mixin config down for a logging
 * feature. A cached reflective read that tries both names degrades to "no row"
 * instead, while the injector anchors themselves stay {@code require = 1} so a
 * genuine method mapping change still fails loudly.</p>
 *
 * <p><b>Attribution.</b> This hook knows the position but never the player;
 * {@link LecternMenuTakeMixin} knows the player. The menu publishes the acting
 * player into {@link MixinAuditSupport} immediately before the adapter call, so
 * the row carries the real name. Automation that removes a book without a menu
 * (a hopper, a mod) gets the {@code #lectern} sentinel, which is the honest
 * answer for it.</p>
 *
 * <p><b>Row shape.</b> {@link network.vonix.guardian.core.action.ActionType#CONTAINER_WITHDRAW}
 * at the lectern coordinates with the book's registry id, true count, and item
 * NBT. Its inverse is {@code tryGiveOrDrop} at that position, which returns the
 * book to the world — the same best-effort restore every container withdrawal
 * already receives when its target cannot accept the item back. The row is
 * therefore reversible at plan time and cannot produce a mid-run failure.</p>
 *
 * <p><b>Placing a book is deliberately not logged.</b> The inverse of
 * {@code CONTAINER_DEPOSIT} is {@code tryRemoveFromContainer}, which requires the
 * block entity to be a {@code Container}; a lectern's is not. A deposit row would
 * be an action the rollback engine is guaranteed to fail on, so emitting one
 * would be strictly worse than emitting none.</p>
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.LecternBlockEntity$1")
public abstract class LecternBookMixin {

    /**
     * Cached handle on the adapter's back-reference to its lectern. Resolved
     * once; {@code null} means this mapping exposes neither known name and the
     * mixin records nothing rather than destabilising the cell.
     */
    private static volatile Field VG$OWNER;
    private static volatile boolean VG$OWNER_RESOLVED;

    /** GUI "take book" button path ({@code LecternMenu#clickMenuButton}, button 3). */
    @Inject(method = "m_8016_(I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            require = 1,
            remap = false)
    private void vg$beforeRemoveNoUpdate(int slot, CallbackInfoReturnable<ItemStack> cir) {
        vg$record(slot);
    }

    /** Generic {@code Container} removal path (hoppers, mods, automation). */
    @Inject(method = "m_7407_(II)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            require = 1,
            remap = false)
    private void vg$beforeRemove(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        vg$record(slot);
    }

    /**
     * Emits the withdrawal row while the book is still attached to the lectern.
     *
     * @param slot container slot; lecterns have exactly one, so any other value
     *             is not a book removal
     */
    private void vg$record(int slot) {
        try {
            if (slot != 0) return;
            LecternBlockEntity lectern = vg$owner(this);
            if (lectern == null) return;
            ItemStack book = lectern.getBook();
            if (book == null || book.isEmpty()) return;
            Level level = lectern.getLevel();
            BlockPos pos = lectern.getBlockPos();
            if (level == null || level.isClientSide || pos == null) return;

            String itemId = MixinAuditSupport.itemId(book);
            if (itemId == null) return;
            EventSubmitter s = MixinAuditSupport.submitter();
            if (s == null) return;

            Player taker = MixinAuditSupport.lecternTaker();
            s.submitContainerChange(
                    taker == null ? null : taker.getUUID(),
                    taker == null ? "#lectern" : taker.getName().getString(),
                    MixinAuditSupport.worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId, -Math.max(1, book.getCount()), "#interact:lectern_take",
                    MixinAuditSupport.itemNbt(book));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Resolves the lectern that owns the adapter instance. The field is
     * synthetic, so both its source name ({@code this$0}) and its SRG name
     * ({@code f_59572_}) are tried, in that order.
     *
     * @param adapter the anonymous container instance ({@code this})
     * @return the owning lectern, or {@code null} when it cannot be resolved
     */
    private static LecternBlockEntity vg$owner(Object adapter) {
        try {
            if (adapter == null) return null;
            Field f = VG$OWNER;
            if (!VG$OWNER_RESOLVED) {
                f = vg$resolveOwnerField(adapter.getClass());
                VG$OWNER = f;
                VG$OWNER_RESOLVED = true;
            }
            if (f == null) return null;
            Object value = f.get(adapter);
            return value instanceof LecternBlockEntity lectern ? lectern : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field vg$resolveOwnerField(Class<?> type) {
        for (String name : new String[]{"this$0", "f_59572_"}) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // try the next spelling
            }
        }
        return null;
    }
}
