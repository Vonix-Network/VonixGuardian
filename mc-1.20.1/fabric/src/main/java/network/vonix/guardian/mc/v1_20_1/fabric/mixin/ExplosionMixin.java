/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EXPLOSION — inject at HEAD of {@code Explosion#finalizeExplosion(boolean)}.
 * {@code toBlow} is populated at that point.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Shadow @Final private Level level;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final private net.minecraft.world.entity.Entity source;

    @Inject(method = "finalizeExplosion(Z)V", at = @At("HEAD"), require = 0)
    private void vg$onFinalize(boolean spawnParticles, CallbackInfo ci) {
        try {
            Explosion self = (Explosion) (Object) this;
            var affected = self.getToBlow();
            if (affected == null || affected.isEmpty()) return;
            FabricMixinBridge.explosion(level, source, x, y, z,
                    java.util.List.copyOf(affected));
        } catch (Throwable ignored) {}
    }
}
