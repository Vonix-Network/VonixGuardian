/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ITEM_DROP — inject at HEAD of {@code Player#drop(ItemStack, boolean, boolean)}.
 * Fires for both Q (single) and Ctrl+Q (whole stack).
 */
@Mixin(Player.class)
public abstract class ItemTossMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            require = 0)
    private void vg$onDrop(ItemStack stack, boolean includeThrowerName, boolean traceItem,
                           CallbackInfoReturnable<ItemEntity> cir) {
        try {
            FabricMixinBridge.itemDrop((Player) (Object) this, stack);
        } catch (Throwable ignored) {}
    }
}
