/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_19_2.common.EntitySentinel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** v1.3.1 X2 — Ledger-parity: EnderDragon.checkWalls -> removeBlock. */
@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Redirect(method = "checkWalls", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$dragonRemoveBlock(ServerLevel level, BlockPos pos, boolean isMoving) {
        BlockState oldState = level.getBlockState(pos);
        boolean removed = level.removeBlock(pos, isMoving);
        if (removed && oldState != null && !oldState.isAir()) {
            EnderDragon self = (EnderDragon) (Object) this;
            vg$submitBreak(self, level, pos, oldState, EntitySentinel.SRC_ENDER_DRAGON);
        }
        return removed;
    }

    private static void vg$submitBreak(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || oldState == null) return;
            String actor = network.vonix.guardian.mc.v1_19_2.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(oldState), "minecraft:air",
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static void vg$submitPlace(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState newState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || newState == null) return;
            String actor = network.vonix.guardian.mc.v1_19_2.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    "minecraft:air", vg$blockId(newState),
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static void vg$submitChange(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, String sourceTag) {
        try {
            network.vonix.guardian.core.Guardian g = network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge.guardian();
            if (g == null) return;
            network.vonix.guardian.core.event.EventSubmitter s = g.submitter();
            if (s == null || level == null || pos == null || oldState == null || newState == null) return;
            String actor = network.vonix.guardian.mc.v1_19_2.common.EntitySentinel.of(entity);
            s.submitEntityChangeBlock(null, actor,
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(oldState), vg$blockId(newState),
                    sourceTag == null ? actor : sourceTag);
        } catch (Throwable ignored) {}
    }

    private static String vg$blockId(net.minecraft.world.level.block.state.BlockState state) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.core.Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
