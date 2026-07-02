/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SIGN_CHANGE — inject at HEAD of {@link ServerGamePacketListenerImpl#handleSignUpdate}
 * so we get raw packet lines the client sent, plus the player who edited.
 * Side (front/back) is embedded in the packet on 1.20+.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignChangeMixin {

    @Shadow public net.minecraft.server.level.ServerPlayer player;

    @Inject(method = "handleSignUpdate(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        try {
            if (player == null || packet == null) return;
            var pos = packet.getPos();
            var level = player.level();
            if (level == null) return;
            BlockEntity be = level.getBlockEntity(pos);
            boolean isFront = true;
            if (be instanceof SignBlockEntity sign) {
                isFront = sign.isFacingFrontText(player);
            }
            FabricMixinBridge.signChange(player, level, pos, packet.getLines(), isFront);
        } catch (Throwable ignored) {}
    }
}
