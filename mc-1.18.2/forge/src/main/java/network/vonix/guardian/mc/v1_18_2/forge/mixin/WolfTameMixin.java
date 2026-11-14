/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinLoggingSupport;

/**
 * Wolf taming — {@code Wolf#mobInteract} calls {@code tame(Player)} on a successful
 * bone feed. Taming transfers ownership of a persistent entity and left no audit
 * trail; the generic Forge {@code EntityInteract} handler fires on every right
 * click, tamed or not, so it cannot distinguish this.
 *
 * <p>The transition is confirmed rather than predicted: {@code isTame()} is read
 * at {@code HEAD} and again at {@code RETURN}, and a row is only emitted when it
 * went {@code false → true}. A failed feed (wrong item, already tamed, wrong
 * hand) therefore produces nothing, and a re-entrant or modded path that tames
 * through some other route also produces nothing — this records exactly what
 * happened in {@code mobInteract}.</p>
 *
 * <p>Emitted as {@link network.vonix.guardian.core.action.ActionType#CLICK}
 * (interaction, audit-only), matching every other lane-7/8/10 player interaction
 * row.</p>
 */
@Mixin(Wolf.class)
public abstract class WolfTameMixin {

    /** Whether the wolf was already tamed when {@code mobInteract} was entered. */
    @Unique
    private boolean vg$wasTame;

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeWolfInteract(Player player, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        try {
            Wolf self = (Wolf) (Object) this;
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
    private void vg$afterWolfInteract(Player player, InteractionHand hand,
                                      CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (vg$wasTame) return;
            Wolf self = (Wolf) (Object) this;
            if (!self.isTame()) return;
            if (player == null || self.level == null) return;
            MixinLoggingSupport.emitInteraction(self, player, "#tame:wolf");
        } catch (Throwable ignored) {
        }
    }
}
