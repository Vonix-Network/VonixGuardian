/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_3.fabric.mixin;

import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.SignTextSlot;
import network.vonix.guardian.mc.v26_3.fabric.FabricMixinBridge;
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
            var pos = packet.pos();
            var level = player.level();
            if (level == null) return;
            boolean isFront = packet.slot() != SignTextSlot.BACK;
            java.util.List<String> lineList = packet.lines();
            String[] lines = lineList == null ? new String[0] : lineList.toArray(new String[0]);
            FabricMixinBridge.signChange(player, level, pos, lines, isFront);
        } catch (Throwable ignored) {}
    }
}
