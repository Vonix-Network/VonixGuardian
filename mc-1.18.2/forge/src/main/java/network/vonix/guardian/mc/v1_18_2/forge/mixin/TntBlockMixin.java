/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.TntPrimeMemory;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.1 X7 — TNT-prime attribution (Forge).
 *
 * <p>All TNT-priming paths in vanilla funnel through the private static
 * {@code TntBlock.explode(Level, BlockPos, LivingEntity)}. Injecting HEAD on
 * that method captures the {@code LivingEntity igniter} for every prime path:
 * fire spread, redstone charge, dispenser flint&amp;steel, or player right-click
 * with flint&amp;steel. When the igniter is a Player, the actor identity is
 * recorded into {@link TntPrimeMemory} keyed by {@code (worldId, pos)}; the
 * {@code ExplosionEvent.Detonate} handler consumes the record and promotes
 * the {@code PrimedTnt} sentinel to a player-scoped attribution.
 *
 * <p>Signature stable across 1.18.2/1.19.2/1.20.1 (verified via javap on
 * srg jars). Method name in Forge dev is {@code explode}; runtime SRG maps
 * to {@code m_57436_} but mixin refmap handles that.
 */
@Mixin(TntBlock.class)
public abstract class TntBlockMixin {

    @Inject(method = "explode(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), require = 0)
    private static void vg$onTntPrime(Level level, BlockPos pos, LivingEntity igniter, CallbackInfo ci) {
        try {
            if (level == null || pos == null || igniter == null) return;
            if (igniter instanceof Player p) {
                vg$recordPlayer(level, pos, p);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void vg$recordPlayer(Level level, BlockPos pos, Player player) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            long now = System.currentTimeMillis();
            g.tntPrimeMemory().record(
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    TntPrimeMemory.PrimeRecord.player(player.getUUID(),
                            player.getName().getString(), now));
        } catch (Throwable ignored) {
        }
    }
}
