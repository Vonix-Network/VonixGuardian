/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge.mixin;

import java.util.function.Predicate;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import network.vonix.guardian.mc.v1_20_1.forge.ForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bounded player-inventory producer with fail-closed method mappings. */
@Mixin(Inventory.class)
public abstract class PlayerInventoryMixin {

    private ItemStack[] vg$inventoryBefore;
    private boolean vg$inWrappedCall;

    @Inject(method = "m_6836_(ILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$onSetItem(int slot, ItemStack after, CallbackInfo ci) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        try {
            vg$runSetItem(slot, after);
            ci.cancel();
        } finally {
            vg$inWrappedCall = false;
        }
    }

    @Inject(method = "m_36040_(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeIndexedAdd(int slot, ItemStack incoming, CallbackInfoReturnable<Boolean> cir) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            cir.setReturnValue(((Inventory) (Object) this).add(slot, incoming));
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_36022_(Ljava/util/function/Predicate;ILnet/minecraft/world/Container;)I", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforePredicateClear(Predicate<ItemStack> predicate, int maxCount, Container container,
                                         CallbackInfoReturnable<Integer> cir) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            cir.setReturnValue(((Inventory) (Object) this).clearOrCountMatchingItems(predicate, maxCount, container));
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_36054_(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeAdd(ItemStack incoming, CallbackInfoReturnable<Boolean> cir) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            cir.setReturnValue(((Inventory) (Object) this).add(incoming));
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_36057_(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeRemove(ItemStack removed, CallbackInfo ci) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            ((Inventory) (Object) this).removeItem(removed);
            ci.cancel();
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_7407_(II)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeIndexedRemove(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            cir.setReturnValue(((Inventory) (Object) this).removeItem(slot, amount));
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_8016_(I)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeNoUpdateRemove(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            cir.setReturnValue(((Inventory) (Object) this).removeItemNoUpdate(slot));
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_36071_()V", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeDropAll(CallbackInfo ci) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            ((Inventory) (Object) this).dropAll();
            ci.cancel();
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    @Inject(method = "m_6211_()V", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vg$beforeClear(CallbackInfo ci) {
        if (vg$inWrappedCall) return;
        vg$inWrappedCall = true;
        vg$beginSnapshot();
        try {
            ((Inventory) (Object) this).clearContent();
            ci.cancel();
        } catch (Throwable t) {
            vg$abortSnapshot();
            throw wrap(t);
        } finally {
            vg$inWrappedCall = false;
            vg$commitSnapshot();
        }
    }

    private void vg$runSetItem(int slot, ItemStack after) {
        Inventory inventory = (Inventory) (Object) this;
        ItemStack before = null;
        Player owner = null;
        try {
            if (vg$inventoryBefore == null
                    && slot >= 0 && slot < inventory.getContainerSize()
                    && after != null) {
                owner = inventory.player;
                if (owner != null) before = inventory.getItem(slot).copy();
            }
        } catch (Throwable ignored) {
            before = null;
            owner = null;
        }
        inventory.setItem(slot, after);
        if (before == null || owner == null) return;
        try {
            ItemStack actual = inventory.getItem(slot);
            if (actual == null) actual = ItemStack.EMPTY;
            ForgeMixinBridge.playerInventorySlotChange(owner, before, actual.copy(), slot);
        } catch (Throwable ignored) { }
    }

    private void vg$beginSnapshot() {
        try {
            if (vg$inventoryBefore != null) return;
            Inventory inventory = (Inventory) (Object) this;
            int size = inventory.getContainerSize();
            ItemStack[] snapshot = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                ItemStack stack = inventory.getItem(i);
                snapshot[i] = stack == null ? ItemStack.EMPTY : stack.copy();
            }
            vg$inventoryBefore = snapshot;
        } catch (Throwable ignored) { vg$inventoryBefore = null; }
    }

    private void vg$abortSnapshot() {
        vg$inventoryBefore = null;
    }

    private void vg$commitSnapshot() {
        if (vg$inventoryBefore != null) vg$finishSnapshot();
    }

    private static RuntimeException wrap(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }

    private void vg$finishSnapshot() {
        ItemStack[] before = vg$inventoryBefore;
        vg$inventoryBefore = null;
        if (before == null) return;
        try {
            Inventory inventory = (Inventory) (Object) this;
            Player owner = inventory.player;
            if (owner == null) return;
            int size = Math.min(before.length, inventory.getContainerSize());
            for (int i = 0; i < size; i++) {
                ItemStack oldStack = before[i] == null ? ItemStack.EMPTY : before[i];
                ItemStack newStack = inventory.getItem(i);
                if (newStack == null) newStack = ItemStack.EMPTY;
                boolean changed = oldStack.isEmpty() != newStack.isEmpty()
                        || oldStack.getCount() != newStack.getCount()
                        || (!oldStack.isEmpty() && oldStack.getItem() != newStack.getItem())
                        || (!oldStack.isEmpty() && !newStack.isEmpty()
                            && oldStack.getItem() == newStack.getItem()
                            && ForgeMixinBridge.inventoryMetadataChanged(owner, oldStack, newStack));
                if (changed) ForgeMixinBridge.playerInventorySlotChange(owner, oldStack, newStack.copy(), i);
            }
        } catch (Throwable ignored) { }
    }
}
