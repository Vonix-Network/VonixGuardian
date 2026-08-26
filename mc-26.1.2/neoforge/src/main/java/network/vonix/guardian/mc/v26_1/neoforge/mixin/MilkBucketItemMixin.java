/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import network.vonix.guardian.mc.v26_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.1 removed MilkBucketItem; milk is a componentized Item/ItemStack path.
 * Capture milk-bucket consume parity via ItemStack.finishUsingItem.
 */
@Mixin(ItemStack.class)
public abstract class MilkBucketItemMixin {

    @Inject(method = "finishUsingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private void vg$emit(Level level, LivingEntity entity,
                         CallbackInfoReturnable<ItemStack> cir) {
        try {
            if (level.isClientSide()) return;
            ItemStack self = (ItemStack) (Object) this;
            if (!self.is(Items.MILK_BUCKET)) return;
            if (!(entity instanceof Player p)) return;
            BlockPos pos = p.blockPosition();
            NeoForgeMixinBridge.bucketEmpty(p, pos, "minecraft:milk_bucket");
        } catch (Throwable ignored) {
        }
    }
}
