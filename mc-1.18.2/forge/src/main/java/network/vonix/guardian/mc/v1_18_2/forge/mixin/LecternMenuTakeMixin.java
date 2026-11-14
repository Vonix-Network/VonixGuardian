/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lectern "book taken" producer — the capture half.
 *
 * <p>The lectern block entity knows the position but never the player; the menu
 * knows the player but not the position. This hook runs at the player's "take
 * book" click and arms a capture carrying the acting player and the exact book
 * that is about to leave the lectern. {@link LecternBookMixin} then consumes that
 * capture from {@code LecternBlockEntity#onBookItemRemove}, which vanilla invokes
 * synchronously inside this same button press, and emits the row with both the
 * position and the attribution.</p>
 *
 * <p><b>Why capture instead of reading the item afterwards.</b> By the time the
 * block entity reports the removal, the stack has already been detached and the
 * container emptied — its identity and NBT are unrecoverable. At HEAD of the
 * click they are exact.</p>
 *
 * <p><b>Why the button number is not trusted alone.</b> {@code BUTTON_TAKE_BOOK}
 * (3) is published here, but nothing is emitted from this half. The emitter
 * verifies the capture exists and the book is non-empty, and clears the capture
 * on RETURN unconditionally, so a click that fails its {@code mayBuild} check —
 * vanilla returns {@code false} without removing anything — leaves no capture
 * behind for the next interaction to mis-attribute.</p>
 *
 * <p><b>Binding.</b> {@code LecternMenu#clickMenuButton} is SRG
 * {@code m_6366_(Player, int)Z}. {@code BUTTON_TAKE_BOOK} is a compile-time
 * {@code int} constant and is inlined, because a mixin injected into another class
 * cannot portably read a {@code static final} field of the merged target through
 * the source name.</p>
 */
@Mixin(LecternMenu.class)
public abstract class LecternMenuTakeMixin {

    /** {@code LecternMenu.BUTTON_TAKE_BOOK} — inlined; see the class note. */
    private static final int VG$BUTTON_TAKE_BOOK = 3;

    /** The lectern's book container: {@code bookAccess} in source, {@code f_39819_} under SRG. */
    @Shadow
    private net.minecraft.world.Container f_39819_;

    @Inject(
            method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeClickMenuButton(Player player, int button,
                                          CallbackInfoReturnable<Boolean> cir) {
        try {
            if (button != VG$BUTTON_TAKE_BOOK) return;
            MixinAuditSupport.pushLecternTake(player, vg$heldBook());
        } catch (Throwable ignored) {
        }
    }

    @Inject(
            method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterClickMenuButton(Player player, int button,
                                         CallbackInfoReturnable<Boolean> cir) {
        try {
            // Unconditional. Covers the take path (already consumed by the block
            // entity hook), the page-turn paths that never armed, and a take that
            // vanilla refused — none of which may leak a capture into the next
            // interaction on this thread.
            MixinAuditSupport.clearLecternTake();
        } catch (Throwable ignored) {
        }
    }

    /**
     * @return the book currently in the lectern, or {@code null} when the
     *         shadowed container is unavailable or empty
     */
    private ItemStack vg$heldBook() {
        try {
            net.minecraft.world.Container access = this.f_39819_;
            if (access == null) return null;
            ItemStack book = access.getItem(0);
            return book == null || book.isEmpty() ? null : book;
        } catch (Throwable t) {
            return null;
        }
    }
}
