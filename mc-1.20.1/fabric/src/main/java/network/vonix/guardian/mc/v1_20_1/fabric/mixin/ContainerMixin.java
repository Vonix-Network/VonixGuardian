/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CONTAINER — mixin on {@link ChestBlockEntity} start/stop open. Only covers
 * chests + trapped chests + ender chests (via inheritance). Barrels/shulkers
 * use RandomizableContainerBlockEntity; for v1.2.0 we ship chest coverage only —
 * TODO(v1.2.1): add BarrelBlockEntity / ShulkerBoxBlockEntity mixins.
 */
@Mixin(ChestBlockEntity.class)
public abstract class ContainerMixin {

    @Inject(method = "startOpen(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onStartOpen(Player player, CallbackInfo ci) {
        try {
            ChestBlockEntity self = (ChestBlockEntity) (Object) this;
            if (self.getLevel() == null) return;
            FabricMixinBridge.containerOpen(player, self.getLevel(), self.getBlockPos(), self);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "stopOpen(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onStopOpen(Player player, CallbackInfo ci) {
        try {
            ChestBlockEntity self = (ChestBlockEntity) (Object) this;
            FabricMixinBridge.containerClose(player, self);
        } catch (Throwable ignored) {}
    }
}
