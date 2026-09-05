/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import network.vonix.guardian.mc.v1_21_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Milestone 1 hopper producer: snapshot both containers, then emit exact-slot
 * pull/push rows after a successful vanilla transfer.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(method = "ejectItems", at = @At("HEAD"), require = 0)
    private static void vg$beforeEjectItems(Level level, BlockPos pos, HopperBlockEntity hopper,
                                            CallbackInfoReturnable<Boolean> cir) {
        try {
            FabricMixinBridge.hopperEjectBegin(level, pos, hopper);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "ejectItems", at = @At("RETURN"), require = 0)
    private static void vg$onEjectItems(Level level, BlockPos pos, HopperBlockEntity hopper,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir != null && Boolean.TRUE.equals(cir.getReturnValue())) {
                FabricMixinBridge.hopperEjectCommit(level, pos, hopper);
            } else {
                FabricMixinBridge.hopperAbort();
            }
        } catch (Throwable ignored) {
            FabricMixinBridge.hopperAbort();
        }
    }

    @Inject(method = "suckInItems", at = @At("HEAD"), require = 0)
    private static void vg$beforeSuckInItems(Level level, Hopper hopper,
                                             CallbackInfoReturnable<Boolean> cir) {
        try {
            FabricMixinBridge.hopperSuckBegin(level, hopper);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "suckInItems", at = @At("RETURN"), require = 0)
    private static void vg$onSuckInItems(Level level, Hopper hopper,
                                         CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir != null && Boolean.TRUE.equals(cir.getReturnValue())) {
                FabricMixinBridge.hopperSuckCommit(level, hopper);
            } else {
                FabricMixinBridge.hopperAbort();
            }
        } catch (Throwable ignored) {
            FabricMixinBridge.hopperAbort();
        }
    }

    @Inject(method = "tryMoveInItem", at = @At("RETURN"), require = 0)
    private static void vg$onTryMoveInItem(Container source, Container destination, ItemStack stack,
                                           int destSlot, Direction direction,
                                           CallbackInfoReturnable<ItemStack> cir) {
        try {
            FabricMixinBridge.hopperMoveSlot(destSlot);
        } catch (Throwable ignored) {}
    }

    static java.util.Map<Integer, ItemStack> vg$snapshot(Container c) {
        return FabricMixinBridge.snapshotContainer(c);
    }
}
