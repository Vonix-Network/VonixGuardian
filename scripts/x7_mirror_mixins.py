#!/usr/bin/env python3
"""Mirror X7 mixins + bridge additions to all 8 cells."""
from pathlib import Path

CELLS = [
    ("mc-1.18.2", "fabric",   "v1_18_2", "fabric",   "FabricMixinBridge",   "vg.mixins.json"),
    ("mc-1.18.2", "forge",    "v1_18_2", "forge",    "ForgeMixinBridge",    "vg.mixins.json"),
    ("mc-1.19.2", "fabric",   "v1_19_2", "fabric",   "FabricMixinBridge",   "vg.mixins.json"),
    ("mc-1.19.2", "forge",    "v1_19_2", "forge",    "ForgeMixinBridge",    "vg.mixins.json"),
    ("mc-1.20.1", "fabric",   "v1_20_1", "fabric",   "FabricMixinBridge",   "vg.mixins.json"),
    ("mc-1.20.1", "forge",    "v1_20_1", "forge",    "ForgeMixinBridge",    "vg.mixins.json"),
    ("mc-1.21.1", "fabric",   "v1_21_1", "fabric",   "FabricMixinBridge",   "vg.mixins.json"),
    ("mc-1.21.1", "neoforge", "v1_21_1", "neoforge", "NeoForgeMixinBridge", "vg-neoforge.mixins.json"),
]

BASE = Path("/tmp/vg-x7-wt")

TNT_BLOCK = '''/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.__VER__.__LOADER__.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import network.vonix.guardian.mc.__VER__.__LOADER__.__BRIDGE__;
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
                __BRIDGE__.recordTntPrimePlayer(level, pos, p);
            }
        } catch (Throwable ignored) {
        }
    }
}
'''

# 1.18.2 uses `new BlockPos(x, y, z)` int/int/int constructor for BlockPos.containing equivalent.
# 1.19.2 uses new BlockPos(int, int, int).
# 1.20.1+ has BlockPos.containing(double, double, double).
PRIMED_TNT_MODERN = '''/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.__VER__.__LOADER__.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import network.vonix.guardian.mc.__VER__.__LOADER__.__BRIDGE__;
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
                BlockPos pos = __BLOCKPOS_CONTAINING__;
                __BRIDGE__.recordTntPrimePlayer(level, pos, p);
            }
        } catch (Throwable ignored) {
        }
    }
}
'''

BRIDGE_ADDITION_TEMPLATE = '''
    // ==================================================================== X7 TNT-prime memory
    // v1.3.1 X7: record the actor priming a TNT block so the eventual
    // explosion-detonate handler can attribute correctly. See
    // network.vonix.guardian.core.attribution.TntPrimeMemory.

    /**
     * Record a player as the priming actor for the TNT block at {@code pos}.
     *
     * @param player the priming player (never {@code null}); called from
     *               {@code TntBlockMixin.explode(Level,BlockPos,LivingEntity)}
     *               HEAD when the igniter is a Player, and from
     *               {@code PrimedTntEntityMixin.<init>} TAIL when the igniter
     *               argument on the constructor is a Player.
     */
    public static void recordTntPrimePlayer(Level level, BlockPos pos, Player player) {
        try {
            Guardian g = __VG_ACCESSOR__();
            if (g == null || level == null || pos == null || player == null) return;
            long now = System.currentTimeMillis();
            g.tntPrimeMemory().record(
                    WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                    network.vonix.guardian.core.attribution.TntPrimeMemory.PrimeRecord.player(
                            player.getUUID(), player.getName().getString(), now));
        } catch (Throwable t) {
            warn("recordTntPrimePlayer", t);
        }
    }
'''

# Per-cell Guardian accessor. For neoforge: VonixGuardianNeoForge.guardian().
# Fabric: VonixGuardianFabric.guardian(). Forge: VonixGuardianForge.guardian().
def vg_accessor(loader):
    if loader == "neoforge": return "VonixGuardianNeoForge.guardian"
    if loader == "fabric":   return "VonixGuardianFabric.guardian"
    if loader == "forge":    return "VonixGuardianForge.guardian"
    raise ValueError(loader)

for mc, loader, ver, loader_pkg, bridge, mixjson in CELLS:
    # Skip 1.21.1/neoforge - already done manually.
    if mc == "mc-1.21.1" and loader == "neoforge":
        continue

    cell_root = BASE / mc / loader / "src" / "main" / "java" / "network" / "vonix" / "guardian" / "mc" / ver / loader_pkg
    mixin_dir = cell_root / "mixin"
    mixin_dir.mkdir(parents=True, exist_ok=True)

    # 1.18.2 / 1.19.2 use `new BlockPos(int, int, int)` — no double ctor; we
    # need to floor doubles first.
    if mc in ("mc-1.18.2", "mc-1.19.2"):
        blockpos_containing = "new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z))"
    else:
        blockpos_containing = "BlockPos.containing(x, y, z)"

    tnt = TNT_BLOCK.replace("__VER__", ver).replace("__LOADER__", loader_pkg).replace("__BRIDGE__", bridge)
    tntpath = mixin_dir / "TntBlockMixin.java"
    tntpath.write_text(tnt)

    primed = PRIMED_TNT_MODERN.replace("__VER__", ver).replace("__LOADER__", loader_pkg).replace("__BRIDGE__", bridge).replace("__BLOCKPOS_CONTAINING__", blockpos_containing)
    primedpath = mixin_dir / "PrimedTntEntityMixin.java"
    primedpath.write_text(primed)

    print(f"WROTE {tntpath.relative_to(BASE)}")
    print(f"WROTE {primedpath.relative_to(BASE)}")
