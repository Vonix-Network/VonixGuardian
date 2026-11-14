/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.support;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;

/**
 * Decides what to do about a block write observed at the chunk chokepoint.
 *
 * <p>This is the fallback layer, not the primary one. The event and mixin layers
 * already record block breaks, places, explosions and the rest <em>with attribution</em>
 * — who did it, from what, with what item. A write seen here has none of that: it knows
 * only that a position changed. So the rule is:</p>
 *
 * <ul>
 *   <li><b>Inside a known operation</b> (an event handler or a rollback is running),
 *       stay silent. The richer layer is about to record it, or already did. Recording
 *       again would double every row in the log.</li>
 *   <li><b>Outside any known operation</b>, the write came from something we do not
 *       instrument — a modded explosion, a custom machine, a dragon's breath, a
 *       teleport that swapped terrain. Record it as an externally-caused change with
 *       the position and both states, so a rollback can still undo it.</li>
 * </ul>
 *
 * <p>The suppression depth is a thread-local because Minecraft's server thread is the
 * only one that writes blocks in practice, and a rollback that runs off-thread must
 * not have its own writes echoed back as grief.</p>
 */
public final class BlockWriteHook {

    /**
     * Depth of "an operation we already understand is in progress".
     *
     * <p>Event handlers and the rollback engine bracket their work with
     * {@link #enter()} / {@link #exit()}. While this is above zero the chokepoint hook
     * observes but does not record.</p>
     */
    private static final ThreadLocal<int[]> SUPPRESS_DEPTH =
        ThreadLocal.withInitial(() -> new int[1]);

    private BlockWriteHook() {
    }

    /** Mark the start of an operation whose writes are already accounted for. */
    public static void enter() {
        SUPPRESS_DEPTH.get()[0]++;
    }

    /** Mark the end of such an operation. Must be paired with {@link #enter()}. */
    public static void exit() {
        int[] d = SUPPRESS_DEPTH.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    /** @return current suppression depth; {@code 0} means unaccounted-for writes are visible */
    public static int depth() {
        return SUPPRESS_DEPTH.get()[0];
    }

    /**
     * Observe a block write.
     *
     * @param level the server level the chunk belongs to
     * @param pos   position being written
     * @param next  state being written
     */
    public static void observe(ServerLevel level, BlockPos pos, BlockState next) {
        if (level == null || pos == null || next == null) {
            return;
        }
        if (SUPPRESS_DEPTH.get()[0] > 0) {
            return;
        }
        // setBlockState is called for no-op writes as well; a state that is the same
        // block and the same properties changed nothing and must not produce a row.
        BlockState previous = level.getBlockState(pos);
        if (previous == next) {
            return;
        }
        // Air-to-air is never interesting (chunk load, lighting, structure voids).
        if (previous.isAir() && next.isAir()) {
            return;
        }
        String worldId = WorldKey.of(level);
        // The attributed layer may already have recorded this exact change - a player
        // break raises BreakEvent and a setBlockState. Without this check every break
        // in the game would appear twice, once with the player's name and once as
        // UNKNOWN, which is worse than not having the hook at all.
        Guardian guardian = ForgeMixinBridge.guardianOrNull();
        if (guardian != null && guardian.recentBlockWrites()
                .recentlyAttributed(worldId, pos.getX(), pos.getY(), pos.getZ(),
                    System.currentTimeMillis())) {
            return;
        }
        // This layer has no attribution and no better information than the states
        // themselves. Hand it to the bridge, which owns the emit path and can decide
        // whether the configured coverage wants it.
        ForgeMixinBridge.onUnattributedBlockChange(
            worldId, level, pos.getX(), pos.getY(), pos.getZ(), previous, next);
    }
}
