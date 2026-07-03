/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.FillCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.neoforge.NeoForgeMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * v1.3.1 X4 — {@code /fill} per-block audit rows.
 *
 * <p>Ledger targets {@code fillBlocks} at the {@code BlockInput.place} INVOKE
 * with a captured {@code @Local BlockPos}. We use a {@link Redirect} on the
 * same INVOKE so we always observe the actual write and can synthesise both a
 * BLOCK_BREAK (for the pre-fill state) and a BLOCK_PLACE (for the post-fill
 * state) per position. Attribution: the command source's player when the
 * source is a player, else {@link network.vonix.guardian.core.event.Sentinel#COMMAND}.</p>
 *
 * <p>The mixin uses {@code require = 0}: on MC versions where the internal
 * helper's signature drifted the mixin silently no-ops; the vanilla command
 * still runs.</p>
 *
 * <p><b>Oversized /fill discipline (server-thread bounded work):</b> the
 * per-position work here is a single {@code getBlockState} + a
 * {@code submitBlockBreak}/{@code submitBlockPlace} call. The
 * {@link network.vonix.guardian.core.storage.BatchedAsyncWriteQueue} handles
 * the off-thread I/O, and each row is a fixed-size Action. A 32k /fill emits
 * up to 65k queue entries but zero string joins — the ExplosionJoinWorker
 * pattern isn't needed here because there is no per-explosion string payload;
 * rows are per-position atomic Actions.</p>
 */
@Mixin(FillCommand.class)
public abstract class FillCommandMixin {

    @Redirect(
        method = "fillBlocks",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/commands/arguments/blocks/BlockInput;place(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)Z"),
        require = 0)
    private static boolean vg$onFillPlace(BlockInput input, ServerLevel world, BlockPos pos, int flags,
                                          CommandSourceStack source) {
        BlockState oldState = world.getBlockState(pos);
        boolean placed = input.place(world, pos, flags);
        if (placed) {
            try {
                Entity ent = source != null ? source.getEntity() : null;
                Player player = ent instanceof Player p ? p : null;
                if (oldState != null && !oldState.isAir()) {
                    NeoForgeMixinBridge.commandBlockBreak(player, world, pos.immutable(), oldState, "cmd:fill");
                }
                BlockState newState = input.getState();
                if (newState != null) {
                    NeoForgeMixinBridge.commandBlockPlace(player, world, pos.immutable(), newState, "cmd:fill");
                }
            } catch (Throwable ignored) {}
        }
        return placed;
    }
}
