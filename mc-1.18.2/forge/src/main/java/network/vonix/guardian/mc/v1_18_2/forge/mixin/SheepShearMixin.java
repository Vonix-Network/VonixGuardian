/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records a completed sheep shearing as one {@link ActionType#CLICK} row.
 *
 * <p>Shearing is the only livestock interaction that destroys stored value
 * without breaking a block, and it left no audit trail. Forge's generic
 * {@code PlayerInteractEvent.RightClickBlock} handler cannot see it (a sheep is
 * an entity, not a block), and the generic entity-interact handler fires for
 * every right click on a sheep — sheared, baby, or empty-handed — so it cannot
 * distinguish "looked at a sheep" from "took its wool".
 *
 * <p>The row is emitted from {@code Sheep.mobInteract} rather than
 * {@code Shearable.shear} because only {@code mobInteract} carries the
 * {@link Player} that did it; {@code shear(SoundSource)} has no actor and is
 * also reachable from automated callers.
 *
 * <p>Binding: packaged Forge 1.18.2 runs the SRG-mapped
 * {@code m_6071_(Player, InteractionHand)}; the target is bound literally with
 * {@code remap = false} and {@code require = 1} so a mapping change aborts the
 * mixin apply instead of silently disabling the producer.
 */
@Mixin(Sheep.class)
public abstract class SheepShearMixin {

    /** {@code #interact:shear} — player-initiated, so deliberately outside the hot-event kill-switch. */
    @Unique
    private static final String vg$shearSource = "#interact:shear";

    @Unique
    private boolean vg$shearPending;

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeShear(Player player, InteractionHand hand,
                                CallbackInfoReturnable<InteractionResult> cir) {
        try {
            vg$shearPending = false;
            if (player == null || hand == null) return;
            Sheep self = (Sheep) (Object) this;
            if (!self.readyForShearing()) return;
            if (player.level == null || player.level.isClientSide) return;
            ItemStack held = player.getItemInHand(hand);
            if (held == null || held.isEmpty() || !held.is(Items.SHEARS)) return;
            vg$shearPending = true;
        } catch (Throwable ignored) {
            vg$shearPending = false;
        }
    }

    @Inject(
            method = "m_6071_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterShear(Player player, InteractionHand hand,
                               CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (!vg$shearPending) return;
            vg$shearPending = false;
            if (player == null) return;
            Sheep self = (Sheep) (Object) this;
            // Vanilla only flips this after wool was actually dropped.
            if (!self.isSheared()) return;
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            String woolId = "minecraft:" + self.getColor().getSerializedName() + "_wool";
            g.submit(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(WorldKey.of(player.level))
                    .position(self.blockPosition().getX(), self.blockPosition().getY(), self.blockPosition().getZ())
                    .targetId(woolId)
                    .sourceTag(vg$shearSource)
                    .build());
        } catch (Throwable ignored) {
            // Mixins must never escape into the server thread.
        }
    }
}
