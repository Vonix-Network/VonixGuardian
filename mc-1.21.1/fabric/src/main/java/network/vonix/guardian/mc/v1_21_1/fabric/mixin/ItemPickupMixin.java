/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ITEM_PICKUP — inject at HEAD of {@code ItemEntity#playerTouch}. The stack
 * count we capture reflects the pre-merge amount; that's the right value for
 * CoreProtect parity (the "how much did the player pick up" number).
 */
@Mixin(ItemEntity.class)
public abstract class ItemPickupMixin {

    @Inject(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onPickup(Player player, CallbackInfo ci) {
        try {
            ItemEntity self = (ItemEntity) (Object) this;
            if (self.level().isClientSide()) return;
            ItemStack copy = self.getItem().copy();
            if (copy.isEmpty()) return;
            FabricMixinBridge.itemPickup(player, copy);
        } catch (Throwable ignored) {}
    }
}
