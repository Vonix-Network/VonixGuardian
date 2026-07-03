/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X2 — Ledger-parity: {@link LightningBolt} fire spread.
 *
 * <p>Vanilla {@code LightningBolt.spawnFire(int)} places fire blocks via
 * {@code ServerLevel.setBlockAndUpdate(pos, state)} twice: once at the impact
 * point (ordinal 0) and once for each spread attempt in a small radius
 * (ordinal 1). Ledger captures both ordinals separately
 * ({@code entities/LightningBoltMixin.java:16-32}). We collapse to a single
 * {@code @Redirect} on the vanilla-mapped signature since the redirect is
 * always applied on both invoke sites — mixin auto-multiplexes the redirect
 * across all matching call sites in the method body, giving the same coverage
 * with less bytecode weight.</p>
 *
 * <p>Actor: the bolt itself. Source tag: {@link EntitySentinel#SRC_LIGHTNING}
 * (mirrors Ledger's {@code #mob:minecraft:lightning_bolt} sourceTag scheme).</p>
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltMixin {

    @Redirect(
            method = "spawnFire",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean vg$lightningFireSpawn(ServerLevel level, BlockPos pos, BlockState newState) {
        boolean placed = level.setBlockAndUpdate(pos, newState);
        if (placed && newState != null && !newState.isAir()) {
            LightningBolt self = (LightningBolt) (Object) this;
            NeoForgeMixinBridge.entityPlace(self, level, pos, newState, EntitySentinel.SRC_LIGHTNING);
        }
        return placed;
    }
}
