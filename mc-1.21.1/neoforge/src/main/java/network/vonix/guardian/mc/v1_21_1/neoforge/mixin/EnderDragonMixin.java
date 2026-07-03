/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import network.vonix.guardian.mc.v1_21_1.common.EntitySentinel;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X2 — Ledger-parity: {@link EnderDragon} head-collision block break.
 *
 * <p>Vanilla {@code EnderDragon.checkWalls(AABB)} scans a box around the dragon's
 * head and destroys any non-{@code #dragon_immune} block via
 * {@code ServerLevel.removeBlock(pos, false)}. Prior to X2 these breaks fired
 * only the aggregate {@code LivingDestroyBlockEvent} on Forge/NeoForge, and
 * missed entirely on Fabric. Ledger tightens this by injecting on the exact
 * {@code removeBlock} INVOKE and capturing the affected block state before
 * removal (see {@code entities/EnderDragonMixin.java:16-30}). We mirror that
 * shape here so an {@code /vg inspect} on a dragon-broken bedrock frame or
 * end-city tower attributes cleanly to {@code #mob:minecraft:ender_dragon} with
 * source-tag {@link EntitySentinel#SRC_ENDER_DRAGON}.</p>
 *
 * <p>Uses {@code @Redirect} rather than {@code @Inject(HEAD)} so the ledger row
 * is only produced when {@code removeBlock} actually returned {@code true}
 * (i.e. the block was really removed). Follows the v1.3.0 W1a/W1b/W1c
 * tightening pattern.</p>
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {

    @Redirect(
            method = "checkWalls",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"
            )
    )
    private boolean vg$dragonRemoveBlock(ServerLevel level, BlockPos pos, boolean isMoving) {
        BlockState oldState = level.getBlockState(pos);
        boolean removed = level.removeBlock(pos, isMoving);
        if (removed && oldState != null && !oldState.isAir()) {
            EnderDragon self = (EnderDragon) (Object) this;
            NeoForgeMixinBridge.entityBreak(self, level, pos, oldState, EntitySentinel.SRC_ENDER_DRAGON);
        }
        return removed;
    }
}
