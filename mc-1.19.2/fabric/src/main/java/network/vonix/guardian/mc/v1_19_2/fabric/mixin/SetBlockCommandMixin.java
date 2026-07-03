/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.SetBlockCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X4 — {@code /setblock} per-block audit row.
 *
 * <p>Redirects the {@code BlockInput.place} INVOKE inside
 * {@code SetBlockCommand.setBlock}. Emits a BLOCK_BREAK (pre) and BLOCK_PLACE
 * (post) row scoped to {@code cmd:setblock}.</p>
 */
@Mixin(SetBlockCommand.class)
public abstract class SetBlockCommandMixin {

    @Redirect(
        method = "setBlock",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/commands/arguments/blocks/BlockInput;place(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)Z"),
        require = 0)
    private static boolean vg$onSetBlockPlace(BlockInput input, ServerLevel world, BlockPos pos, int flags,
                                              CommandSourceStack source) {
        BlockState oldState = world.getBlockState(pos);
        boolean placed = input.place(world, pos, flags);
        if (placed) {
            try {
                Entity ent = source != null ? source.getEntity() : null;
                Player player = ent instanceof Player p ? p : null;
                if (oldState != null && !oldState.isAir()) {
                    FabricMixinBridge.commandBlockBreak(player, world, pos.immutable(), oldState, "cmd:setblock");
                }
                BlockState newState = input.getState();
                if (newState != null) {
                    FabricMixinBridge.commandBlockPlace(player, world, pos.immutable(), newState, "cmd:setblock");
                }
            } catch (Throwable ignored) {}
        }
        return placed;
    }
}
