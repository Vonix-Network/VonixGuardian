/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.1 X7 — TNT-prime attribution (belt-and-braces to {@code TntBlockMixin}).
 *
 * <p>Some modpacks add TNT variants that skip the vanilla
 * {@code TntBlock.explode} path and spawn {@code PrimedTnt} directly. This
 * mixin captures the {@code LivingEntity igniter} constructor argument
 * whenever a Player is present, keyed by the entity's block position (which
 * matches the pre-conversion TntBlock position within 1 block).
 *
 * <p>Records into
 * {@link network.vonix.guardian.core.attribution.TntPrimeMemory}; the
 * explosion-detonate handler consumes the record and promotes the sentinel
 * to a player-scoped attribution.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntEntityMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at = @At("TAIL"), require = 0)
    private void vg$onLivingCtor(Level level, double x, double y, double z, LivingEntity igniter, CallbackInfo ci) {
        try {
            if (level == null || igniter == null) return;
            if (igniter instanceof Player p) {
                BlockPos pos = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
                FabricMixinBridge.recordTntPrimePlayer(level, pos, p);
            }
        } catch (Throwable ignored) {
        }
    }
}
