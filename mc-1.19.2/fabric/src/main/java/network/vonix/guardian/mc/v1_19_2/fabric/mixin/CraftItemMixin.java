/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ITEM_CRAFT — inject at HEAD of {@link ResultSlot#onTake}. Called each time
 * the player takes an item from the crafting-table result slot; also covers
 * shift-click bulk craft (called once per output stack).
 */
@Mixin(ResultSlot.class)
public abstract class CraftItemMixin {

    @Inject(method = "onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onTake(Player player, ItemStack stack, CallbackInfo ci) {
        try {
            FabricMixinBridge.itemCraft(player, stack.copy());
        } catch (Throwable ignored) {}
    }
}
