/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_20_1.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures concrete-powder solidification as BlockForm parity on Fabric. */
@Mixin(ConcretePowderBlock.class)
public abstract class ConcretePowderBlockMixin {

    @Redirect(method = "onLand", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean vg$onLandSet(Level level, BlockPos pos, BlockState newState, int flags) {
        BlockState oldState = level.getBlockState(pos);
        boolean changed = level.setBlock(pos, newState, flags);
        if (changed && newState != null && oldState != null && !oldState.is(newState.getBlock())) {
            FabricMixinBridge.blockForm(level, pos, newState, "world:form:concrete_powder");
        }
        return changed;
    }

    @Inject(method = "updateShape", at = @At("RETURN"))
    private void vg$updateShapeForm(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos, CallbackInfoReturnable<BlockState> cir) {
        try {
            BlockState result = cir.getReturnValue();
            if (result == null || state == null || result.is(state.getBlock())) return;
            if (level instanceof Level realLevel) {
                FabricMixinBridge.blockForm(realLevel, pos, result, "world:form:concrete_powder_neighbor");
            }
        } catch (Throwable ignored) {
        }
    }
}
