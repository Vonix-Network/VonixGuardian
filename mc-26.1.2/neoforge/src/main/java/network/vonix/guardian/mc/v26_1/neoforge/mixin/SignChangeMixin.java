/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.neoforge.mixin;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import network.vonix.guardian.mc.v26_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
            NeoForgeMixinBridge.signChange(player, level, pos, packet.getLines(), isFront);
        } catch (Throwable ignored) {}
    }
}
