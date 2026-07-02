/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_18_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BLOCK_PLACE — capture the moment a {@link BlockItem} successfully places a
 * block. We inject at HEAD of {@code updateBlockStateFromTag} — a hot path that
 * runs post-place and lets us read the final placed state cleanly — is
 * unnecessary; instead we hook the {@code place(BlockPlaceContext)} return.
 *
 * <p>Handler is intentionally forgiving: any failure short-circuits silently.
 */
@Mixin(BlockItem.class)
public abstract class BlockPlaceMixin {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 0)
    private void vg$onPlace(BlockPlaceContext ctx, CallbackInfoReturnable<net.minecraft.world.InteractionResult> cir) {
        try {
            if (cir.getReturnValue() == null || !cir.getReturnValue().consumesAction()) return;
            Player p = ctx.getPlayer();
            Level level = ctx.getLevel();
            BlockPos pos = ctx.getClickedPos();
            if (p == null || level == null || pos == null) return;
            BlockState placed = level.getBlockState(pos);
            FabricMixinBridge.blockPlace(p, level, pos, placed);
        } catch (Throwable ignored) {
            // Never poison the interaction pipeline.
        }
    }
}
