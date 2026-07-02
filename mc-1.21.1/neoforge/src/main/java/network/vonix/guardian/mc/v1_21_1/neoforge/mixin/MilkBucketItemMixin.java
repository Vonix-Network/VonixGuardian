/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.level.Level;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_21_1.common.WorldKey;
import network.vonix.guardian.mc.v1_21_1.neoforge.VonixGuardianNeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A9 / W5-02 — NeoForge 1.21.1 milk-bucket parity mixin.
 *
 * <p>Milk buckets don't traverse {@code BucketItem.use}; they go through the
 * food-consume path via {@link MilkBucketItem#finishUsingItem}. CoreProtect
 * treats a milk drink as a {@code BUCKET_EMPTY} at the drinker's block
 * position (fluid = {@code minecraft:milk_bucket}), so we mirror that.
 */
@Mixin(MilkBucketItem.class)
public abstract class MilkBucketItemMixin {

    private static final Logger VG$LOG = LoggerFactory.getLogger("VonixGuardian/MilkBucketMixin");

    @Inject(method = "finishUsingItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private void vg$emit(ItemStack stack, Level level, LivingEntity entity,
                         CallbackInfoReturnable<ItemStack> cir) {
        try {
            if (level.isClientSide) return;
            if (!(entity instanceof Player p)) return;

            Guardian g = VonixGuardianNeoForge.guardian();
            EventSubmitter s = g == null ? null : g.submitter();
            if (s == null) return;

            BlockPos pos = p.blockPosition();
            s.submitBucketEmpty(p.getUUID(), p.getName().getString(),
                    WorldKey.of(p.level()),
                    pos.getX(), pos.getY(), pos.getZ(),
                    "minecraft:milk_bucket", null);
        } catch (Throwable t) {
            VG$LOG.warn(Guardian.MARKER, "MilkBucketItemMixin submit failed", t);
        }
    }
}
