/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_3.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import network.vonix.guardian.mc.v26_3.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * EXPLOSION — 26.x replaced {@code Explosion#finalizeExplosion} / {@code getToBlow}
 * with {@link ServerExplosion}. Blocks are destroyed in {@code interactWithBlocks}
 * after {@code calculateExplodedPositions}; inject at HEAD of that private method
 * so the audit row sees the same affected-block list vanilla is about to break.
 */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private Entity source;

    @Inject(method = "interactWithBlocks(Ljava/util/List;)V", at = @At("HEAD"), require = 1)
    private void vg$onInteractWithBlocks(List<BlockPos> targetBlocks, CallbackInfo ci) {
        try {
            if (targetBlocks == null || targetBlocks.isEmpty() || center == null) return;
            FabricMixinBridge.explosion(level, source, center.x, center.y, center.z,
                    List.copyOf(targetBlocks));
        } catch (Throwable ignored) {}
    }
}
