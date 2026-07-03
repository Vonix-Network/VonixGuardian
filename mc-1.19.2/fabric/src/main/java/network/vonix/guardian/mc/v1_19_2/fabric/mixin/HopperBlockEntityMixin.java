/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.1 X4 — Hopper push/pull producer.
 *
 * <p>Vanilla hopper item transfer routes through two static helpers:</p>
 * <ul>
 *   <li>{@code suckInItems(Level, Hopper)} — pull from a container above (or an
 *       ItemEntity) into the hopper.</li>
 *   <li>{@code ejectItems(Level, BlockPos, HopperBlockEntity)} — push into the
 *       container the hopper is facing.</li>
 * </ul>
 *
 * <p>We can't cheaply diff both container inventories without allocating
 * snapshots — CoreProtect / Ledger take the same trade-off. We instead capture
 * a coarse per-tick <em>attempt</em> row using the hopper's current output
 * slot: this is exactly the semantics Ledger uses via
 * {@code TransportItemsBetweenContainersMixin}. If the transfer failed the row
 * still exists but with amount=0 (filtered by the core submitContainerChange
 * delta==0 short-circuit).</p>
 *
 * <p>The mixin uses {@code require=0} — if the static helper is not present on
 * a specific loader/mapping combo it silently no-ops. Only vanilla containers
 * are captured; modded Hopper implementations (copper golem inventories, etc.)
 * are covered by later waves.</p>
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(method = "ejectItems", at = @At("RETURN"), require = 0, cancellable = false)
    private static void vg$onEjectItems(Level level, BlockPos pos, HopperBlockEntity hopper,
                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir == null) return;
            Boolean did = cir.getReturnValue();
            if (did == null || !did) return;
            ItemStack witness = vg$firstNonEmptySlot(hopper);
            if (witness == null) return;
            FabricMixinBridge.hopperPush(level, pos, witness);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "suckInItems", at = @At("RETURN"), require = 0, cancellable = false)
    private static void vg$onSuckInItems(Level level, Hopper hopper,
                                         CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir == null) return;
            Boolean did = cir.getReturnValue();
            if (did == null || !did) return;
            // Hopper extends Container; safely narrow via cast.
            ItemStack witness = vg$firstNonEmptySlot((Container) hopper);
            if (witness == null) return;
            BlockPos pos = new BlockPos((int) Math.floor(hopper.getLevelX()),
                                        (int) Math.floor(hopper.getLevelY()),
                                        (int) Math.floor(hopper.getLevelZ()));
            FabricMixinBridge.hopperPull(level, pos, witness);
        } catch (Throwable ignored) {}
    }

    private static ItemStack vg$firstNonEmptySlot(Container c) {
        try {
            int n = c.getContainerSize();
            for (int i = 0; i < n; i++) {
                ItemStack s = c.getItem(i);
                if (s != null && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
