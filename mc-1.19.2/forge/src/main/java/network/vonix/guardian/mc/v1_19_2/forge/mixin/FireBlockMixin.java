/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_19_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures fire tick (block-burn side-effects) and fire placement (ignite).
 *
 * <p>v1.3.0 W1a — tightened: replaced HEAD injections on {@code tick} that
 * submitted a BURN Action on every random-tick regardless of whether fire
 * actually consumed a block. We now @Redirect the inner
 * {@code level.removeBlock(pos, moving)} / {@code level.setBlock(pos, state, flags)}
 * calls inside {@code tick}/{@code checkBurnOut} and only submit when those
 * calls actually removed / replaced a block.</p>
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$fireTickRemove(ServerLevel level, BlockPos targetPos, boolean moving) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.removeBlock(targetPos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            vg$submitBurn(level, targetPos, oldState);
        }
        return changed;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$fireTickSet(ServerLevel level, BlockPos targetPos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.setBlock(targetPos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                vg$submitBurn(level, targetPos, oldState);
            } else {
                vg$submitIgnite(level, targetPos, newState);
            }
        }
        return changed;
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean vg$checkBurnOutRemove(Level level, BlockPos targetPos, boolean moving) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.removeBlock(targetPos, moving);
        if (changed && oldState != null && !oldState.isAir()) {
            vg$submitBurn(level, targetPos, oldState);
        }
        return changed;
    }

    @Redirect(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$checkBurnOutSet(Level level, BlockPos targetPos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(targetPos);
        boolean changed = level.setBlock(targetPos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            if (newState.isAir()) {
                vg$submitBurn(level, targetPos, oldState);
            } else {
                vg$submitIgnite(level, targetPos, newState);
            }
        }
        return changed;
    }

    @Inject(method = "onPlace", at = @At("TAIL"))
    private void vg$onFirePlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving, CallbackInfo ci) {
        try {
            if (state == null || level == null || pos == null) return;
            if (oldState != null && oldState.is(state.getBlock())) return;
            if (level.getBlockState(pos).is(state.getBlock())) {
                vg$submitIgnite(level, pos, state);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void vg$submitBurn(Level level, BlockPos pos, BlockState state) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            s.submitBurn(null, "#fire",
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(state), "world:burn");
        } catch (Throwable ignored) {
        }
    }

    private static void vg$submitIgnite(Level level, BlockPos pos, BlockState state) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            EventSubmitter s = g.submitter();
            if (s == null) return;
            s.submitIgnite(null, "#fire",
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    vg$blockId(state), "world:ignite");
        } catch (Throwable ignored) {
        }
    }

    private static String vg$blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
