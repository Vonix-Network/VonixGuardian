/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import network.vonix.guardian.mc.v26_1_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * EXPLOSION — 26.2 replaced {@code Explosion} (class with
 * {@code finalizeExplosion}/{@code getToBlow}) with the {@code Explosion}
 * interface plus {@link ServerExplosion}. Affected positions are handed to
 * private {@code interactWithBlocks(List)} immediately before blocks are
 * destroyed, which is the equivalent capture point.
 */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private Entity source;

    @Inject(method = "interactWithBlocks(Ljava/util/List;)V", at = @At("HEAD"), require = 1)
    private void vg$onInteractWithBlocks(List<BlockPos> affected, CallbackInfo ci) {
        try {
            if (affected == null || affected.isEmpty()) return;
            FabricMixinBridge.explosion(level, source, center.x, center.y, center.z,
                    List.copyOf(affected));
        } catch (Throwable ignored) {}
    }
}
