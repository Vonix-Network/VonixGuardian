/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Short-term memory of "who emptied a fluid bucket where and when" — used by
 * the v1.3.1 X3 {@code LiquidBlockMixin} producer pipeline to attribute a
 * water/lava spread cell back to the player whose bucket-empty was the
 * ancestor of the flow, so long as it happened within a bounded time and
 * distance.
 *
 * <p><strong>Contract vs CoreProtect.</strong> CoreProtect's
 * {@code BlockFromToListener} attributes a fluid spread to the player who
 * originally placed the source block via its {@code queueBlockPlace} entry.
 * VG has no cross-tick placement queue that maps a spread coord back to a
 * source coord, so we mirror CP's semantics with a bounded time+distance
 * heuristic: a spread within {@code TTL_MS} of a bucket-empty and within
 * {@code MAX_RADIUS_MANHATTAN} blocks of it is attributed to the emptying
 * player; anything past that limit is attributed to the {@code #fluid}
 * sentinel.</p>
 *
 * <p><strong>Concurrency.</strong> The record path
 * ({@link #recordBucketEmpty}) is called from the server thread on the
 * bucket-use TAIL inject. The lookup path ({@link #lookup}) is also called
 * from the server thread (from inside the {@code shouldSpreadLiquid} mixin).
 * We still guard with a {@link ReentrantLock} to preserve safety if a mod
 * ever off-threads the fluid tick — Ledger has observed this in the wild.</p>
 *
 * <p><strong>Bounded memory.</strong> The internal ring buffer is capped at
 * {@link #MAX_ENTRIES} events; older entries are dropped on insert. Combined
 * with the {@link #TTL_MS} cutoff on read, steady-state memory stays under
 * ~1 KiB even during a bucket-spam griefing raid.</p>
 *
 * @since 1.3.1 (X3)
 */
public final class FluidSourceMemory {

    /** Traceback time-to-live for bucket-empty → fluid-spread attribution. */
    public static final long TTL_MS = 2L * 60L * 1000L; // 2 minutes

    /**
     * Manhattan distance in blocks that a flow may travel from the emptying
     * position before attribution falls back to {@link
     * network.vonix.guardian.core.event.Sentinel#FLUID}. Chosen to match
     * CoreProtect's practical fluid-flow attribution radius: water can spread
     * 7 blocks horizontally per source, and lava 3 — we generously round up
     * to 8 so a source-block placed one over from its final resting place is
     * still caught.
     */
    public static final int MAX_RADIUS_MANHATTAN = 8;

    /** Ring-buffer cap. */
    public static final int MAX_ENTRIES = 4096;

    private final long ttlMs;
    private final int maxRadius;
    private final int maxEntries;
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * Per-position most-recent record. Position key is packed as
     * {@code (worldId, x, y, z)}; older entries at the same position are
     * overwritten. Backed by a {@code HashMap} — expected steady-state size is
     * a few dozen keys because bucket-empty events are rare relative to the
     * flow-tick rate they seed.
     */
    private final Map<Key, Record> byPos = new HashMap<>();
    /** Insertion-order FIFO for cheap over-cap eviction. */
    private final Deque<Key> insertOrder = new ArrayDeque<>();

    public FluidSourceMemory() {
        this(TTL_MS, MAX_RADIUS_MANHATTAN, MAX_ENTRIES);
    }

    /** Test-only constructor. */
    public FluidSourceMemory(long ttlMs, int maxRadius, int maxEntries) {
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        if (maxRadius < 0) throw new IllegalArgumentException("maxRadius must be >= 0");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        this.ttlMs = ttlMs;
        this.maxRadius = maxRadius;
        this.maxEntries = maxEntries;
    }

    /**
     * Record a bucket-empty at {@code (x,y,z)} by {@code actorUuid} /
     * {@code actorName}. Producers call this from the bucket-use TAIL inject
     * after the fluid has been placed.
     *
     * @param worldId dimension key, not {@code null}
     * @param x       block x
     * @param y       block y
     * @param z       block z
     * @param actorUuid emptying player UUID, {@code null} is accepted but the
     *                  entry is useless (kept for symmetry with sentinel calls)
     * @param actorName emptying player name (or sentinel)
     * @param nowMs   wall-clock time in ms; injected for test determinism
     */
    public void recordBucketEmpty(String worldId, int x, int y, int z,
                                  UUID actorUuid, String actorName, long nowMs) {
        if (worldId == null) return;
        lock.lock();
        try {
            Key k = new Key(worldId, x, y, z);
            if (byPos.put(k, new Record(actorUuid, actorName, nowMs)) == null) {
                insertOrder.addLast(k);
            }
            // Bounded eviction: drop oldest keys until capacity is respected.
            while (insertOrder.size() > maxEntries) {
                Key evict = insertOrder.pollFirst();
                if (evict != null) byPos.remove(evict);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Look up a bucket-empty ancestor for a spread cell at {@code (x,y,z)}.
     *
     * <p>Walks candidate source positions within
     * {@link #MAX_RADIUS_MANHATTAN} of the spread cell in the same world; the
     * closest non-expired candidate wins. Ties broken by most-recent record.</p>
     *
     * @param worldId dimension key, not {@code null}
     * @param x       spread cell x
     * @param y       spread cell y
     * @param z       spread cell z
     * @param nowMs   wall-clock time in ms
     * @return the source record, or {@code null} if no live ancestor exists
     */
    public Record lookup(String worldId, int x, int y, int z, long nowMs) {
        if (worldId == null) return null;
        long cutoff = nowMs - ttlMs;
        lock.lock();
        try {
            Record best = null;
            int bestDist = Integer.MAX_VALUE;
            Iterator<Map.Entry<Key, Record>> it = byPos.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Key, Record> e = it.next();
                Record r = e.getValue();
                if (r.timestampMs < cutoff) {
                    // TTL-expired: opportunistically evict.
                    it.remove();
                    insertOrder.remove(e.getKey());
                    continue;
                }
                Key k = e.getKey();
                if (!k.worldId.equals(worldId)) continue;
                int dist = Math.abs(k.x - x) + Math.abs(k.y - y) + Math.abs(k.z - z);
                if (dist > maxRadius) continue;
                if (dist < bestDist || (dist == bestDist && best != null && r.timestampMs > best.timestampMs)) {
                    best = r;
                    bestDist = dist;
                }
            }
            return best;
        } finally {
            lock.unlock();
        }
    }

    /** @return current entry count. Test helper. */
    public int size() {
        lock.lock();
        try { return byPos.size(); }
        finally { lock.unlock(); }
    }

    /** Immutable value class for a recorded bucket-empty. */
    public static final class Record {
        public final UUID actorUuid;
        public final String actorName;
        public final long timestampMs;

        public Record(UUID actorUuid, String actorName, long timestampMs) {
            this.actorUuid = actorUuid;
            this.actorName = actorName;
            this.timestampMs = timestampMs;
        }
    }

    private static final class Key {
        final String worldId;
        final int x, y, z;

        Key(String worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return x == k.x && y == k.y && z == k.z && worldId.equals(k.worldId);
        }

        @Override
        public int hashCode() {
            int h = worldId.hashCode();
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }
    }
}
