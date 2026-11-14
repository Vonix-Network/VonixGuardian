/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.rollback;

import java.util.List;
import java.util.Objects;

/**
 * Where a preview would touch the world, so a client can show it before anything
 * changes.
 *
 * <p>A preview has always reported counts — "468 steps, 309 skipped" — but an
 * operator deciding whether to run a rollback over a griefed base needs to see
 * <em>which</em> blocks. This carries the positions, capped, for a client to draw.
 * It is deliberately not a full plan: the point is a visual sanity check, and a
 * redstone farm can produce hundreds of thousands of rows that no client can
 * usefully draw.</p>
 *
 * <p>Session state, not storage state. A preview outline is discarded when the
 * result is dropped; nothing here is persisted and nothing survives a restart.</p>
 *
 * @param blocks positions to mark, at most {@code total} distinct entries
 * @param total  blocks the plan would touch in full, which may exceed
 *               {@code blocks.size()} when the cap was hit
 */
public record PreviewOutline(List<Block> blocks, int total) {

    /** Block positions beyond this are summarised by {@link #total()} rather than listed. */
    public static final int DEFAULT_CAP = 512;

    /** One position a preview would change. */
    public record Block(String worldId, int x, int y, int z, String blockId) {
        public Block {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    /** An outline with nothing to draw — used for non-preview results. */
    public static PreviewOutline empty() {
        return new PreviewOutline(List.of(), 0);
    }

    public PreviewOutline {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        total = Math.max(total, blocks.size());
    }

    /** @return {@code true} when there is something to render */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** @return positions that were dropped because {@link #DEFAULT_CAP} was reached */
    public int omitted() {
        return Math.max(0, total - blocks.size());
    }

    /**
     * Build an outline from planned actions, stopping at {@code cap}.
     *
     * @param planned actions the plan would dispatch, in dispatch order
     * @param cap     maximum positions to carry
     * @return the outline, with {@code total} always the full planned count
     */
    public static PreviewOutline fromPlanned(List<network.vonix.guardian.core.action.Action> planned,
                                             int cap) {
        if (planned == null || planned.isEmpty()) {
            return empty();
        }
        int limit = Math.max(0, cap);
        List<Block> out = new java.util.ArrayList<>(Math.min(planned.size(), limit));
        for (var a : planned) {
            if (out.size() >= limit) {
                break;
            }
            out.add(new Block(a.worldId(), a.x(), a.y(), a.z(), a.targetId()));
        }
        return new PreviewOutline(out, planned.size());
    }

    /** Build with {@link #DEFAULT_CAP}. */
    public static PreviewOutline fromPlanned(List<network.vonix.guardian.core.action.Action> planned) {
        return fromPlanned(planned, DEFAULT_CAP);
    }
}
