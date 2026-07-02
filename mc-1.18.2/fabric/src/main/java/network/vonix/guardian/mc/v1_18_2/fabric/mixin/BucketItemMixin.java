/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BUCKET_FILL / BUCKET_EMPTY — inject at RETURN of {@link BucketItem#use}.
 *
 * <p>Detection heuristic: plain bucket in hand ⇒ FILL, otherwise ⇒ EMPTY.
 * We ray-trace inline (mirrors the private {@code Item#getPlayerPOVHitResult})
 * to locate the affected block position.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    @Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            at = @At("RETURN"),
            require = 0)
    private void vg$onUse(Level level, Player player, InteractionHand hand,
                          CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        try {
            var res = cir.getReturnValue();
            if (res == null || !res.getResult().consumesAction()) return;
            ItemStack held = player.getItemInHand(hand);
            boolean empty = held.getItem() != Items.BUCKET; // else = filling
            HitResult hr = rayTrace(level, player, empty ? ClipContext.Fluid.NONE : ClipContext.Fluid.SOURCE_ONLY);
            if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) return;
            BlockPos pos = bhr.getBlockPos();
            String heldId = Registry.ITEM.getKey(held.getItem()).toString();
            FabricMixinBridge.bucketUse(player, level, pos, heldId, empty);
        } catch (Throwable ignored) {}
    }

    /** Minimal player-POV ray-trace mirroring the vanilla private helper. */
    private static HitResult rayTrace(Level level, Player player, ClipContext.Fluid fluid) {
        float xRot = player.getXRot();
        float yRot = player.getYRot();
        Vec3 eye = player.getEyePosition();
        float cosPitch = (float) Math.cos(-yRot * ((float) Math.PI / 180F) - (float) Math.PI);
        float sinPitch = (float) Math.sin(-yRot * ((float) Math.PI / 180F) - (float) Math.PI);
        float cosYaw = (float) -Math.cos(-xRot * ((float) Math.PI / 180F));
        float sinYaw = (float) Math.sin(-xRot * ((float) Math.PI / 180F));
        float dx = sinPitch * cosYaw;
        float dz = cosPitch * cosYaw;
        double reach = 5.0D;
        Vec3 to = eye.add((double) dx * reach, (double) sinYaw * reach, (double) dz * reach);
        return level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, fluid, player));
    }
}
