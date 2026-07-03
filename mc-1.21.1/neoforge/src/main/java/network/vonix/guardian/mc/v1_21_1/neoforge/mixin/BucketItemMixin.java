/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 1.21.1 bucket parity mixin.
 *
 * <p>This mixin is observe-only. It calls only the NeoForge mixin bridge so the
 * mixin pre-processor never has to resolve shaded core classes during boot.</p>
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    private static final ThreadLocal<BlockPos> VG$CLICK_POS = new ThreadLocal<>();
    private static final ThreadLocal<String> VG$PRE_BLOCK = new ThreadLocal<>();

    @Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            at = @At("HEAD"))
    private void vg$captureTarget(Level level, Player player, InteractionHand hand,
                                  CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        try {
            ItemStack held = player.getItemInHand(hand);
            Item item = held.getItem();
            ClipContext.Fluid mode = (item == Items.BUCKET) ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
            HitResult hit = Item.getPlayerPOVHitResult(level, player, mode);
            if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = bhr.getBlockPos();
                if (item != Items.BUCKET) {
                    BlockState hitState = level.getBlockState(pos);
                    if (!hitState.canBeReplaced() && bhr.getDirection() != null) {
                        pos = pos.relative(bhr.getDirection());
                    }
                }
                VG$CLICK_POS.set(pos.immutable());
                if (item == Items.BUCKET) {
                    VG$PRE_BLOCK.set(NeoForgeMixinBridge.blockId(level.getBlockState(pos)));
                }
            }
        } catch (Throwable t) {
            VG$CLICK_POS.remove();
            VG$PRE_BLOCK.remove();
        }
    }

    @Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            at = @At("RETURN"))
    private void vg$emit(Level level, Player player, InteractionHand hand,
                         CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        try {
            if (level.isClientSide) return;
            InteractionResultHolder<ItemStack> r = cir.getReturnValue();
            if (r == null) return;
            InteractionResult ir = r.getResult();
            if (ir != InteractionResult.CONSUME && ir != InteractionResult.SUCCESS) return;

            BlockPos pos = VG$CLICK_POS.get();
            if (pos == null) return;

            Item self = (Item) (Object) this;
            if (self == Items.BUCKET) {
                String fluidId = VG$PRE_BLOCK.get();
                NeoForgeMixinBridge.bucketFill(player, pos, fluidId == null ? "minecraft:water" : fluidId);
            } else {
                String selfId = NeoForgeMixinBridge.itemId(self);
                String fluid = selfId.endsWith("_bucket")
                        ? "minecraft:" + selfId.substring(selfId.lastIndexOf(':') + 1).replace("_bucket", "")
                        : selfId;
                NeoForgeMixinBridge.bucketEmpty(player, pos, fluid);
            }
        } catch (Throwable ignored) {
        } finally {
            VG$CLICK_POS.remove();
            VG$PRE_BLOCK.remove();
        }
    }
}
