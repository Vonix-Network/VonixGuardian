/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lectern "book taken" producer — attribution half.
 *
 * <p>{@link LecternBookMixin} sees the lectern and the book but never the player;
 * a lectern's container adapter carries no reference to whoever opened it. This
 * hook is the reverse: {@code LecternMenu#clickMenuButton} receives the acting
 * player but not the block position. The take button runs the adapter's removal
 * synchronously inside this call, so publishing the player here — and clearing it
 * on the way out — gives the adapter hook exact attribution with no guesswork and
 * no cross-request leakage.</p>
 *
 * <p>Only {@code BUTTON_TAKE_BOOK} (3) is published. Page turns (1, 2) and
 * page-jump buttons (100+) change no inventory state, so publishing for them
 * would risk attributing an unrelated removal on the same thread to a player who
 * was only turning pages.</p>
 *
 * <p><b>Binding.</b> {@code LecternMenu#clickMenuButton} is SRG
 * {@code m_6366_(Player, int)Z}. The constant {@code BUTTON_TAKE_BOOK} is a
 * compile-time {@code int} (3) inlined here as a literal, because a mixin
 * injected into a different class cannot portably read a {@code static final}
 * through the merged classloader.</p>
 */
@Mixin(LecternMenu.class)
public abstract class LecternMenuTakeMixin {

    /** {@code LecternMenu.BUTTON_TAKE_BOOK} — inlined; see the class note. */
    private static final int VG$BUTTON_TAKE_BOOK = 3;

    @Inject(
            method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeClickMenuButton(Player player, int button,
                                          CallbackInfoReturnable<Boolean> cir) {
        try {
            if (button == VG$BUTTON_TAKE_BOOK) {
                MixinAuditSupport.pushLecternTaker(player);
            }
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
            // Unconditional: covers the take path, the non-take paths that never
            // published, and a take that threw before returning.
            MixinAuditSupport.clearLecternTaker();
        } catch (Throwable ignored) {
        }
    }
}
