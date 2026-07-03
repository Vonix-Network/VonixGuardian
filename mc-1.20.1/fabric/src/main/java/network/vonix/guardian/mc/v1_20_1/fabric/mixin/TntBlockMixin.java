/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v1.3.1 X7 — TNT-prime attribution.
 *
 * <p>All TNT-priming paths in vanilla funnel through the private static
 * {@code TntBlock.explode(Level, BlockPos, LivingEntity)}:
 * <ul>
 *   <li>{@code onCaughtFire} — fire spread or dispenser-flint&amp;steel</li>
 *   <li>{@code neighborChanged} — redstone charge</li>
 *   <li>{@code use}/{@code interact} — player right-click with flint&amp;steel</li>
 *   <li>{@code wasExploded} — chained TNT / creeper</li>
 *   <li>{@code onProjectileHit} — burning-arrow ignition</li>
 * </ul>
 *
 * <p>Injecting HEAD on that single method lets us record every prime with the
 * {@code LivingEntity igniter} (may be {@code null} for fire spread / redstone /
 * pure environmental sources). For player-attributable ignitions the actor is
 * captured into {@link network.vonix.guardian.core.attribution.TntPrimeMemory}
 * keyed by {@code (worldId, pos)}; the eventual explosion-detonate handler
 * consumes the record via
 * {@link network.vonix.guardian.core.attribution.UniversalAttribution#resolveTntPrime}
 * and promotes the {@code PrimedTnt} sentinel to a player-scoped attribution.
 * Closes CoreProtect-parity gap G-CP-2.
 *
 * <p>Null-igniter cases (fire spread with no known lighter, direct redstone
 * chain, dispenser-of-flint-and-steel without a placer chain) fall through
 * to the existing sentinel path with no worse behaviour than pre-X7.
 *
 * <p>Signature stable from 1.18.2 through 1.21.1 (verified via javap on
 * server-srg jar and confirmed by mojmap reference).
 */
@Mixin(TntBlock.class)
public abstract class TntBlockMixin {

    @Inject(method = "explode(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), require = 0)
    private static void vg$onTntPrime(Level level, BlockPos pos, LivingEntity igniter, CallbackInfo ci) {
        try {
            if (level == null || pos == null || igniter == null) return;
            if (igniter instanceof Player p) {
                FabricMixinBridge.recordTntPrimePlayer(level, pos, p);
            }
        } catch (Throwable ignored) {
        }
    }
}
