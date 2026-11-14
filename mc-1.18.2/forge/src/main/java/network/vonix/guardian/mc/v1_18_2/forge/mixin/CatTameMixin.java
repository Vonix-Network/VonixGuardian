/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cat taming — {@code Cat#mobInteract} calls {@code tame(Player)} on a successful
 * raw-fish feed.
 *
 * <p>Confirmed by reading {@code isTame()} at {@code HEAD} and again at
 * {@code RETURN}, emitting only on a {@code false → true} transition. A stray cat
 * that wanders off, a fed-but-already-tamed cat, or a feed with the wrong item
 * all produce nothing.</p>
 *
 * <p>See {@link WolfTameMixin} for why {@code CLICK} (audit-only) is the right
 * type rather than a fabricated rollback claim.</p>
 */
@Mixin(Cat.class)
public abstract class CatTameMixin {

    /** Whether the cat was already tamed when {@code mobInteract} was entered. */
    @Unique
    private boolean vg$wasTame;

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeCatInteract(Player player, InteractionHand hand,
                                      CallbackInfoReturnable<InteractionResult> cir) {
        try {
            Cat self = (Cat) (Object) this;
            vg$wasTame = self.isTame();
        } catch (Throwable ignored) {
            vg$wasTame = true;
        }
    }

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterCatInteract(Player player, InteractionHand hand,
                                     CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (vg$wasTame) return;
            Cat self = (Cat) (Object) this;
            if (!self.isTame()) return;
            if (player == null || self.level == null) return;
            MixinLoggingSupport.emitInteraction(self, player, "#tame:cat");
        } catch (Throwable ignored) {
        }
    }
}
