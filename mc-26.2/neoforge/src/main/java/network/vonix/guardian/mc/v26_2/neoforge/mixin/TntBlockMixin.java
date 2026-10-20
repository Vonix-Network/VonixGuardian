/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_2.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import network.vonix.guardian.mc.v26_2.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * v1.3.1 X7 — TNT-prime attribution.
 *
 * <p>All TNT-priming paths in vanilla funnel through the private static
 * {@code TntBlock.prime(Level, BlockPos, LivingEntity)} (renamed from
 * {@code explode} and now returning {@code boolean} on 26.2):
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
 * keyed by {@code (worldId, pos)}; the eventual
 * {@code ExplosionEvent.Detonate} handler consumes the record via
 * {@link network.vonix.guardian.core.attribution.UniversalAttribution#consumeTntPrime}
 * and promotes the {@code PrimedTnt} sentinel to a player-scoped
 * {@link network.vonix.guardian.core.attribution.Attribution}. Closes
 * CoreProtect-parity gap G-CP-2.
 *
 * <p>Null-igniter cases (fire spread with no known lighter, direct redstone
 * chain, dispenser-of-flint-and-steel without a placer chain) fall through
 * to the existing sentinel path with no worse behaviour than pre-X7. Future
 * waves can extend {@link network.vonix.guardian.core.attribution.TntPrimeMemory}
 * with a fire-source memory to close those subcases.
 */
@Mixin(TntBlock.class)
public abstract class TntBlockMixin {

    @Inject(method = "prime(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), require = 1)
    private static void vg$onTntPrime(Level level, BlockPos pos, LivingEntity igniter, CallbackInfoReturnable<Boolean> cir) {
        recordPrime(level, pos, igniter);
    }

    /**
     * Chained TNT: {@code wasExploded} builds the {@code PrimedTnt} itself and never
     * routes through {@code prime}, so without this hook a chain prime (blast or
     * creeper) carries no igniter. 26.3 hooks the same method; this closes the only
     * functional delta between the two NeoForge cells.
     */
    @Inject(method = "wasExploded", at = @At("HEAD"), require = 1)
    private void vg$onTntChainPrime(ServerLevel level, BlockPos pos, Explosion explosion, CallbackInfo ci) {
        LivingEntity igniter = explosion == null ? null : explosion.getIndirectSourceEntity();
        recordPrime(level, pos, igniter);
    }

    private static void recordPrime(Level level, BlockPos pos, LivingEntity igniter) {
        try {
            if (level == null || pos == null || igniter == null) return;
            if (igniter instanceof Player p) {
                NeoForgeMixinBridge.recordTntPrimePlayer(level, pos, p);
            }
        } catch (Throwable ignored) {
        }
    }
}
