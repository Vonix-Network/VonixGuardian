/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.18.2 Fabric API has no AFTER_DEATH / ALLOW_DAMAGE. Natural deaths (fall,
 * drown, lava) miss AFTER_KILLED_OTHER_ENTITY; this mixin covers that path
 * and records player damage for attribution.
 */
@Mixin(LivingEntity.class)
public abstract class LivingDeathMixin {

    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onDie(DamageSource source, CallbackInfo ci) {
        try {
            FabricMixinBridge.livingDeath((LivingEntity) (Object) this, source);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("HEAD"),
            require = 0)
    private void vg$onHurt(DamageSource source, float amount, CallbackInfo ci) {
        try {
            FabricMixinBridge.livingHurt((LivingEntity) (Object) this, source);
        } catch (Throwable ignored) {}
    }
}
