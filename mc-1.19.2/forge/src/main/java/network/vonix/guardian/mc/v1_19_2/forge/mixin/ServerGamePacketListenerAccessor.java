/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code ServerGamePacketListenerImpl.player} without {@code @Shadow}.
 * Other mods (Better Combat) also mixin this class; a unique accessor method
 * avoids the {@code @Shadow field player was not located} apply failure.
 *
 * <p>Packaged Forge runtime uses SRG {@code f_9743_}; {@code remap = false}
 * so the accessor does not depend on a named {@code player} refmap lookup.
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
    @Accessor(value = "f_9743_", remap = false)
    ServerPlayer vg$getPlayer();
}
