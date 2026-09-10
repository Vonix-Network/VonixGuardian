/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignChangeMixin {

    @Inject(method = "handleSignUpdate(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onSignUpdate(ServerboundSignUpdatePacket packet, CallbackInfo ci) {
        try {
            ServerPlayer player = ((ServerGamePacketListenerAccessor) (ServerGamePacketListenerImpl) (Object) this).vg$getPlayer();
            if (player == null || packet == null) return;
            var pos = packet.getPos();
            var level = player.level;
            if (level == null) return;
            BlockEntity be = level.getBlockEntity(pos);
            boolean isFront = true;
            ForgeMixinBridge.signChange(player, level, pos, packet.getLines(), isFront);
        } catch (Throwable ignored) {}
    }
}
