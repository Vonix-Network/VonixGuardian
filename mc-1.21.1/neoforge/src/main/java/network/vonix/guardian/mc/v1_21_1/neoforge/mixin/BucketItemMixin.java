/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * A9 / W5-02 — NeoForge 1.21.1 bucket parity mixin.
 *
 * <p>NeoForge removed {@code FillBucketEvent} in the 1.20→1.21 refactor. To
 * preserve CoreProtect-parity `BUCKET_FILL` / `BUCKET_EMPTY` logs we observe
 * vanilla {@link BucketItem#use(Level, Player, InteractionHand)} directly.
 *
 * <p>We <b>observe</b> only — no {@code @Redirect} / {@code @ModifyArg}. HEAD
 * capture stashes the click target + pre-state via a {@link ThreadLocal}, and
 * RETURN checks the {@link InteractionResultHolder} for
 * {@link InteractionResult#CONSUME} / {@link InteractionResult#SUCCESS} before
 * submitting. Empty {@link Items#BUCKET} in hand → {@code submitBucketFill};
 * any other bucket → {@code submitBucketEmpty}.
 *
 * <p>Milk buckets do not route through {@code BucketItem} — see
 * {@link MilkBucketItemMixin}.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    private static final Logger VG$LOG = LoggerFactory.getLogger("VonixGuardian/BucketMixin");

    /** Per-thread capture — server-thread scoped, always cleared in RETURN's finally. */
    private static final ThreadLocal<BlockPos> VG$CLICK_POS = new ThreadLocal<>();
    private static final ThreadLocal<String>   VG$PRE_BLOCK = new ThreadLocal<>();

    @Inject(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;",
            at = @At("HEAD"))
    private void vg$captureTarget(Level level, Player player, InteractionHand hand,
                                  CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        try {
            ItemStack held = player.getItemInHand(hand);
            Item item = held.getItem();
            // Empty bucket = fill (raytrace fluid SOURCE_ONLY). Filled bucket = empty (NONE).
            ClipContext.Fluid mode = (item == Items.BUCKET)
                    ? ClipContext.Fluid.SOURCE_ONLY
                    : ClipContext.Fluid.NONE;
            HitResult hit = Item.getPlayerPOVHitResult(level, player, mode);
            if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = bhr.getBlockPos();
                if (item != Items.BUCKET) {
                    // Empty into world: target block is the one adjacent to the hit face
                    // if the hit block isn't replaceable. The vanilla code re-resolves,
                    // but for logging we want the placed-liquid position:
                    BlockState hitState = level.getBlockState(pos);
                    if (!hitState.canBeReplaced() && bhr.getDirection() != null) {
                        pos = pos.relative(bhr.getDirection());
                    }
                }
                VG$CLICK_POS.set(pos.immutable());
                if (item == Items.BUCKET) {
                    // Capture pre-state block/fluid id for FILL attribution.
                    BlockState state = level.getBlockState(pos);
                    VG$PRE_BLOCK.set(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                }
            }
        } catch (Throwable t) {
            // Never leak from a mixin — abandon the capture and let vanilla proceed.
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

            Guardian g = VonixGuardianNeoForge.guardian();
            EventSubmitter s = g == null ? null : g.submitter();
            if (s == null) return;

            BlockPos pos = VG$CLICK_POS.get();
            if (pos == null) return;

            String worldId = WorldKey.of(player.level());
            // (Object) cast avoids type-arg conversion on the intrinsic Item ref.
            Item self = (Item) (Object) this;
            String selfId = BuiltInRegistries.ITEM.getKey(self).toString();

            if (self == Items.BUCKET) {
                // FILL — bucket gained content, block turned to air.
                String fluidId = VG$PRE_BLOCK.get();
                if (fluidId == null) fluidId = "minecraft:water";
                s.submitBucketFill(player.getUUID(), player.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(),
                        fluidId, null);
            } else {
                // EMPTY — bucket lost content; use the held-bucket id minus `_bucket`
                // suffix as the fluid hint (parity with ForgeEvents 1.20.1 path).
                String fluid = selfId.endsWith("_bucket")
                        ? "minecraft:" + selfId.substring(selfId.lastIndexOf(':') + 1)
                                                .replace("_bucket", "")
                        : selfId;
                s.submitBucketEmpty(player.getUUID(), player.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(),
                        fluid, null);
            }
        } catch (Throwable t) {
            VG$LOG.warn(Guardian.MARKER, "BucketItemMixin submit failed", t);
        } finally {
            VG$CLICK_POS.remove();
            VG$PRE_BLOCK.remove();
        }
    }
}
