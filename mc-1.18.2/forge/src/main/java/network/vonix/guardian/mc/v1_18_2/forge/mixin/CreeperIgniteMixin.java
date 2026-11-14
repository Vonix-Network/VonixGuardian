/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinLoggingSupport;

/**
 * Creeper ignition — a player using flint and steel on a creeper calls
 * {@code Creeper#ignite()}, which arms the entity and leads to an explosion.
 *
 * <p>This is the attribution gap that matters most in the whole entity group:
 * the explosion itself is already recorded, but it is recorded against the
 * {@code #creeper} sentinel, because by detonation time the player is nowhere in
 * the causal chain. Recording the ignition is what lets an operator answer
 * "who set this off?" — the subsequent blast rows can then be read against a
 * known actor and position.</p>
 *
 * <p>Confirmed rather than predicted: {@code isIgnited()} is read at {@code HEAD}
 * and again at {@code RETURN}, and the row is emitted only on a
 * {@code false → true} transition. Flint and steel in the off hand, a click that
 * lands in the wrong place, or a click on an already-armed creeper therefore
 * produce nothing. Deserialization also calls {@code ignite()}, but that path
 * runs through {@code readAdditionalSaveData}, which this mixin does not touch.</p>
 *
 * <p>Emitted as {@link network.vonix.guardian.core.action.ActionType#CLICK}
 * (interaction, audit-only) — the creeper's eventual blast is logged separately
 * by the explosion pipeline, so claiming this row is rollbackable would double
 * count the event.</p>
 */
@Mixin(Creeper.class)
public abstract class CreeperIgniteMixin {

    /** Whether the creeper was already armed when {@code mobInteract} was entered. */
    @Unique
    private boolean vg$wasIgnited;

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeCreeperInteract(Player player, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        try {
            Creeper self = (Creeper) (Object) this;
            vg$wasIgnited = self.isIgnited();
        } catch (Throwable ignored) {
            vg$wasIgnited = true;
        }
    }

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterCreeperInteract(Player player, InteractionHand hand,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (vg$wasIgnited) return;
            Creeper self = (Creeper) (Object) this;
            if (!self.isIgnited()) return;
            if (player == null || self.level == null) return;
            MixinLoggingSupport.emitInteraction(self, player, "#creeper:ignite");
        } catch (Throwable ignored) {
        }
    }
}
