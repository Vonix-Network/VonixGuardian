/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_3.fabric.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import network.vonix.guardian.mc.v26_3.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ITEM_DROP — 26.3 moved toss from {@code Player#drop(ItemStack,ZZ)} to
 * {@code ServerPlayer#drop(ItemStack, boolean, Prediction)}.
 */
@Mixin(ServerPlayer.class)
public abstract class ItemTossMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"),
            require = 1)
    private void vg$onDrop(ItemStack stack, boolean includeThrowerName, Prediction prediction,
                           CallbackInfoReturnable<ItemEntity> cir) {
        try {
            FabricMixinBridge.itemDrop((ServerPlayer) (Object) this, stack);
        } catch (Throwable ignored) {}
    }
}
