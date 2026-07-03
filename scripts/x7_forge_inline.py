#!/usr/bin/env python3
"""Rewrite Forge TntBlockMixin/PrimedTntEntityMixin to inline the record call
(Forge cells have no ForgeMixinBridge helper)."""
from pathlib import Path

BASE = Path("/tmp/vg-x7-wt")

# For each Forge cell, we need the correct Registry import (pre-1.20 → net.minecraft.core.Registry,
# 1.20+ → net.minecraft.core.registries.BuiltInRegistries). We don't actually need Registry for the
# tnt-prime mixins because we don't turn a Block into an id. Only Level.dimension().location().toString().
CELLS = [
    ("mc-1.18.2", "v1_18_2", "BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z))"),
    ("mc-1.19.2", "v1_19_2", "BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z))"),
    ("mc-1.20.1", "v1_20_1", "BlockPos.containing(x, y, z)"),
]

TNT_TEMPLATE = '''/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.__VER__.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.TntPrimeMemory;
import network.vonix.guardian.mc.__VER__.forge.VonixGuardianForge;
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
'''

PRIMED_TEMPLATE = '''/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.__VER__.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.TntPrimeMemory;
import network.vonix.guardian.mc.__VER__.forge.VonixGuardianForge;
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
                BlockPos pos = new __BPOS__;
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
'''

# For 1.20.1 which has BlockPos.containing static, replace "new BlockPos.containing" with "BlockPos.containing"
for mc, ver, bpos in CELLS:
    mixin_dir = BASE / mc / "forge" / "src" / "main" / "java" / "network" / "vonix" / "guardian" / "mc" / ver / "forge" / "mixin"

    tnt = TNT_TEMPLATE.replace("__VER__", ver)
    (mixin_dir / "TntBlockMixin.java").write_text(tnt)

    primed = PRIMED_TEMPLATE.replace("__VER__", ver).replace("new __BPOS__", ("new " + bpos) if bpos.startswith("BlockPos((") else bpos)
    (mixin_dir / "PrimedTntEntityMixin.java").write_text(primed)

    print(f"REWROTE {mc}/forge Tnt* mixins")
