/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import network.vonix.guardian.core.rollback.PreviewOutline;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a rollback preview in the world by sending the client a temporary block
 * over each position the plan would change.
 *
 * <p>A preview has always been text — "468 steps, 309 skipped" — which tells an
 * operator how much would change but not <em>where</em>. Reviewing a griefed base
 * means seeing the shape. This paints a translucent marker at each planned
 * position, client-side only: the server world is never touched, so a preview
 * still cannot alter anything.</p>
 *
 * <p><b>Why {@code sendBlockChange}.</b> It is a per-player packet, so two staff
 * can look at different previews at the same time, and nothing needs undoing in
 * the world. The previous state is tracked so {@link #clear} can restore what the
 * client showed before.</p>
 *
 * <p><b>Limits.</b> Markers are capped by {@link PreviewOutline} so a redstone
 * farm cannot flood the connection. Positions in unloaded chunks are skipped
 * rather than force-loaded — loading chunks to draw a preview would cost the same
 * TPS the operator is trying to protect.</p>
 */
public final class PreviewRenderer {

    /** Marker block. Glass is visible, cheap, and obviously not a real restore. */
    private static final BlockState MARKER = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();

    /** What a player was shown, so {@link #clear} can put it back. */
    private static final Map<UUID, Map<BlockPos, BlockState>> SHOWN = new HashMap<>();

    private PreviewRenderer() {
    }

    /**
     * Paint the outline for one player.
     *
     * @param player  recipient; client-side changes only affect this player
     * @param outline positions to mark
     * @return how many markers were actually sent
     */
    public static int show(ServerPlayer player, PreviewOutline outline) {
        if (player == null || outline == null || outline.isEmpty()) {
            return 0;
        }
        clear(player);
        Map<BlockPos, BlockState> saved = new HashMap<>();
        int sent = 0;
        for (PreviewOutline.Block b : outline.blocks()) {
            ServerLevel level = levelFor(player, b.worldId());
            if (level == null) {
                continue;
            }
            BlockPos pos = new BlockPos(b.x(), b.y(), b.z());
            // Loading a chunk to draw a preview would cost the TPS the operator
            // is protecting, so skip anything not already loaded.
            if (!level.isLoaded(pos)) {
                continue;
            }
            saved.putIfAbsent(pos, level.getBlockState(pos));
            // Send the MARKER, not the block that is there: the client is being
            // shown what the plan would change, not what already exists.
            player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(pos, MARKER));
            sent++;
        }
        SHOWN.put(player.getUUID(), saved);
        return sent;
    }

    /**
     * Remove any markers this renderer placed for the player, restoring what the
     * client showed before.
     *
     * @param player recipient
     * @return how many positions were restored
     */
    public static int clear(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Map<BlockPos, BlockState> saved = SHOWN.remove(player.getUUID());
        if (saved == null || saved.isEmpty()) {
            return 0;
        }
        ServerLevel level = player.getLevel();
        int restored = 0;
        for (Map.Entry<BlockPos, BlockState> e : saved.entrySet()) {
            if (!level.isLoaded(e.getKey())) {
                continue;
            }
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
                e.getKey(), e.getValue()));
            restored++;
        }
        return restored;
    }

    /** Drop any tracked markers for a player who disconnected. */
    public static void forget(UUID playerId) {
        if (playerId != null) {
            SHOWN.remove(playerId);
        }
    }

    /**
     * The one-line caption that goes with the markers, or {@code null} when there
     * is nothing to draw.
     */
    public static MutableComponent caption(PreviewOutline outline) {
        if (outline == null || outline.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Preview: ")
            .append(outline.blocks().size())
            .append(" of ").append(outline.total()).append(" position(s) marked");
        if (outline.omitted() > 0) {
            sb.append(" — ").append(outline.omitted()).append(" omitted for size");
        }
        return ChatRenderer.plain(sb.toString());
    }

    private static ServerLevel levelFor(ServerPlayer player, String worldId) {
        if (worldId == null) {
            return null;
        }
        for (ServerLevel level : player.getServer().getAllLevels()) {
            if (level.dimension().location().toString().equals(worldId)) {
                return level;
            }
        }
        return null;
    }

    /** Marker block state, exposed for tests that assert it is not a real material. */
    static BlockState markerState() {
        return MARKER;
    }
}
