/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.TntPrimeMemory;
import network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.1 X7 — TNT-prime attribution (Forge; belt-and-braces to
 * {@code TntBlockMixin}). Catches modded TNT variants that spawn
 * {@code PrimedTnt} directly without going through the vanilla
 * {@code TntBlock.explode} path.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntEntityMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/entity/LivingEntity;)V", at = @At("TAIL"), require = 0)
    private void vg$onLivingCtor(Level level, double x, double y, double z, LivingEntity igniter, CallbackInfo ci) {
        try {
            if (level == null || igniter == null) return;
            if (igniter instanceof Player p) {
                BlockPos pos = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
                Guardian g = VonixGuardianForge.guardian();
                if (g == null) return;
                long now = System.currentTimeMillis();
                g.tntPrimeMemory().record(
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        TntPrimeMemory.PrimeRecord.player(p.getUUID(),
                                p.getName().getString(), now));
            }
        } catch (Throwable ignored) {
        }
    }
}
