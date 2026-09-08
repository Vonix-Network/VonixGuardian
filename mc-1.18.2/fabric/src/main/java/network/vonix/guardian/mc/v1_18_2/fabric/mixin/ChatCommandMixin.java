/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.18.2 Fabric has no ServerMessageEvents. Chat and slash-commands share
 * {@code ServerboundChatPacket}; commands start with {@code /}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ChatCommandMixin {

    @Shadow public net.minecraft.server.level.ServerPlayer player;

    @Inject(method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onChat(ServerboundChatPacket packet, CallbackInfo ci) {
        try {
            if (player == null || packet == null) return;
            String message = packet.getMessage();
            if (message == null || message.isEmpty()) return;
            if (message.charAt(0) == '/') {
                FabricMixinBridge.commandMessage(player, message);
            } else {
                FabricMixinBridge.chatMessage(player, message);
            }
        } catch (Throwable ignored) {}
    }
}
