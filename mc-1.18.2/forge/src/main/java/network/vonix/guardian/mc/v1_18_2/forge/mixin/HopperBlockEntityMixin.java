/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Milestone 1 hopper producer: snapshot both containers, then emit exact-slot
 * pull/push rows after a successful vanilla transfer.
 *
 * <p>Packaged Forge keeps the patched {@code ejectItems} name, but {@code suckInItems}
 * and {@code tryMoveInItem} are SRG {@code m_155552_} / {@code m_59320_}.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(
            method = "ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private static void vg$beforeEjectItems(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper,
                                            CallbackInfoReturnable<Boolean> cir) {
        try {
            ForgeMixinBridge.hopperEjectBegin(level, pos, hopper);
        } catch (Throwable ignored) {}
    }

    @Inject(
            method = "ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private static void vg$onEjectItems(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir != null && Boolean.TRUE.equals(cir.getReturnValue())) {
                ForgeMixinBridge.hopperEjectCommit(level, pos, hopper);
            } else {
                ForgeMixinBridge.hopperAbort();
            }
        } catch (Throwable ignored) {
            ForgeMixinBridge.hopperAbort();
        }
    }

    @Inject(
            method = "m_155552_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private static void vg$beforeSuckInItems(Level level, Hopper hopper,
                                             CallbackInfoReturnable<Boolean> cir) {
        try {
            ForgeMixinBridge.hopperSuckBegin(level, hopper);
        } catch (Throwable ignored) {}
    }

    @Inject(
            method = "m_155552_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private static void vg$onSuckInItems(Level level, Hopper hopper,
                                         CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir != null && Boolean.TRUE.equals(cir.getReturnValue())) {
                ForgeMixinBridge.hopperSuckCommit(level, hopper);
            } else {
                ForgeMixinBridge.hopperAbort();
            }
        } catch (Throwable ignored) {
            ForgeMixinBridge.hopperAbort();
        }
    }

    @Inject(
            method = "m_59320_(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private static void vg$onTryMoveInItem(Container source, Container destination, ItemStack stack,
                                           int destSlot, Direction direction,
                                           CallbackInfoReturnable<ItemStack> cir) {
        try {
            ForgeMixinBridge.hopperMoveSlot(destSlot);
        } catch (Throwable ignored) {}
    }
}
