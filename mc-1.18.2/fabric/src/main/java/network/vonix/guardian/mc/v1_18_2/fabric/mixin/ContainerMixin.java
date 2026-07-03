/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CONTAINER — mixin on the concrete {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity}
 * subclasses that actually override {@code startOpen} / {@code stopOpen}:
 * {@link ChestBlockEntity} (also covers trapped chests and ender chests through
 * inheritance), {@link BarrelBlockEntity}, and {@link ShulkerBoxBlockEntity}.
 *
 * <p><b>v1.3.1 X9 — Ledger parity widening.</b> Prior to X9 this mixin only
 * targeted {@link ChestBlockEntity}, leaving barrels and shulker boxes to the
 * slot-level {@code AbstractContainerMenuMixin} path (the {@code NOTE(v1.2.6)}
 * block that previously lived here). Ledger targets
 * {@code BaseContainerBlockEntity} through its {@code BaseContainerBlockEntityMixin}
 * for slot attribution AND drives per-container open/close through the concrete
 * subclasses. We now do the same: widening the {@code @Mixin} value list gives
 * barrels + shulkers first-class open/close diff logging identical to chests.</p>
 *
 * <p><b>Why not target {@code BaseContainerBlockEntity} directly?</b> The
 * {@code startOpen} / {@code stopOpen} methods are declared on the
 * {@link net.minecraft.world.Container} interface as {@code default} no-ops.
 * {@code BaseContainerBlockEntity} does not override them, so an
 * {@code @Inject(method = "startOpen(...)")} against the base class silently
 * fails to bind (the descriptor is not present on the class). Injecting on the
 * concrete subclasses that DO override the methods (chest/barrel/shulker) is
 * the correct spongepowered pattern.</p>
 *
 * <p><b>Not covered by this mixin (intentional):</b> hoppers, dispensers,
 * droppers, furnaces (regular / smoker / blast), and brewing stands do not
 * override {@code startOpen} / {@code stopOpen} — their content mutation is
 * observed at the slot level via {@code AbstractContainerMenu#clicked}
 * (already wired) or via the fabric hopper transfer mixins added in the X4
 * hopper-producer wave. Compound (double) chests are covered because the
 * event fires on each half-chest independently.</p>
 */
@Mixin({ChestBlockEntity.class, BarrelBlockEntity.class, ShulkerBoxBlockEntity.class})
public abstract class ContainerMixin {

    @Inject(method = "startOpen(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onStartOpen(Player player, CallbackInfo ci) {
        try {
            BlockEntity self = (BlockEntity) (Object) this;
            Level level = self.getLevel();
            if (level == null) return;
            BlockPos pos = self.getBlockPos();
            // All three targets implement Container (via BaseContainerBlockEntity
            // -> Container). Passing `self` directly to the bridge; the bridge
            // accepts net.minecraft.world.Container.
            FabricMixinBridge.containerOpen(player, level, pos,
                    (net.minecraft.world.Container) self);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "stopOpen(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onStopOpen(Player player, CallbackInfo ci) {
        try {
            BlockEntity self = (BlockEntity) (Object) this;
            FabricMixinBridge.containerClose(player,
                    (net.minecraft.world.Container) self);
        } catch (Throwable ignored) {}
    }
}
