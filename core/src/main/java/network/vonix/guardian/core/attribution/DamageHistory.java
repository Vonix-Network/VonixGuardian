/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded ring of "player X hit entity Y at time T" records, used by the
 * universal attribution chain to credit "berserk mob" griefing back to the
 * player that provoked the mob.
 *
 * <p>Loader-side {@code LivingHurtEvent} / {@code AllowDamageCallback} hooks call
 * {@link #record(UUID, UUID, long)} on every damage event where the attacker is a
 * player. {@link #lastPlayerToHit(UUID, long, long)} answers the resolver chain.
 *
 * <p>Memory is bounded by a configured max entry count with eviction of the
 * oldest victim entry (lowest {@link Hit#timestamp}) on overflow. Each victim
 * only stores the latest player + ts; this is enough for the "recent damage
 * window" attribution heuristic.
 *
 * <p>Thread-safe. Designed to be called from the server tick thread (low contention).
 *
 * @since 0.1.0
 */
public final class DamageHistory {

    /** Default window: 20 seconds — matches CoreProtect's heuristic. */
    public static final long DEFAULT_WINDOW_MILLIS = 20_000L;

    /** Default cap: 10k victim entries. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final long windowMillis;
    private final int maxEntries;
    private final Map<UUID, Hit> hits = new ConcurrentHashMap<>();
    private final AtomicLong evictions = new AtomicLong();
    /**
     * Timestamp-ordered live index. {@link #evictOldestLocked()} always
     * removes the lowest {@link Hit#timestamp}, matching the prior
      * PriorityQueue selection even when callers supply out-of-order times.
     * Overwrite/forget/clear remove the previous node so the index cannot
     * grow past the live map.
     */
    private final TreeSet<IndexNode> byTimestamp = new TreeSet<>();
    private final HashMap<UUID, IndexNode> indexByVictim = new HashMap<>();
    /** Guards hits mutations, the timestamp index, and {@link #clear()}. */
    private final Object stateLock = new Object();
    private long indexSeq;
    /**
     * v1.3.1 X6 (P1-2): counter of insertions observed while {@code size > maxEntries}.
     * We only invoke {@link #evictOldestLocked()} every {@link #EVICT_STRIDE}th over-cap
     * insert, amortizing eviction across many events. Between sweeps the map
     * may transiently overshoot the cap by up to {@code EVICT_STRIDE} entries — a
     * negligible heap price for taking a full oldest-entry selection off the server tick.
     */
    private final AtomicLong evictCounter = new AtomicLong();
    /** Amortization stride: run the oldest-entry sweep every 64th over-cap insert. */
    static final int EVICT_STRIDE = 64;

    public DamageHistory() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_ENTRIES);
    }

    public DamageHistory(long windowMillis, int maxEntries) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be > 0");
        }
        if (maxEntries < 16) {
            throw new IllegalArgumentException("maxEntries must be >= 16");
        }
        this.windowMillis = windowMillis;
        this.maxEntries = maxEntries;
    }

    /** Record that {@code attacker} damaged {@code victim} at {@code timestampMs}. */
    public void record(UUID victim, UUID attacker, long timestampMs) {
        if (victim == null || attacker == null) {
            return;
        }
        Hit hit = new Hit(attacker, timestampMs);
        synchronized (stateLock) {
            Hit prev = hits.put(victim, hit);
            if (prev != null) {
                removeIndex(victim);
            }
            IndexNode node = new IndexNode(timestampMs, ++indexSeq, victim, hit);
            byTimestamp.add(node);
            indexByVictim.put(victim, node);
            if (hits.size() > maxEntries) {
                // v1.3.1 X6 (P1-2): amortized eviction. Under sustained combat the map sits
                // at cap and every damage event would otherwise pay an O(n) sweep on the
                // server tick. Only run the sweep every EVICT_STRIDE-th over-cap insert;
                // between sweeps the map overshoots by up to EVICT_STRIDE entries (~1 KB
                // heap), which is trivially cheaper than 63 wasted O(n) scans.
                if ((evictCounter.incrementAndGet() % EVICT_STRIDE) == 0L) {
                    evictOldestLocked();
                }
            }
        }
    }

    /**
     * @param victim     the entity whose attacker we want
     * @param nowMillis  current time
     * @param windowMs   custom lookback window (use {@link #windowMillis()} for the default)
     * @return the player UUID who last hit {@code victim} inside the window, or {@code null}
     */
    public UUID lastPlayerToHit(UUID victim, long nowMillis, long windowMs) {
        if (victim == null) {
            return null;
        }
        Hit h = hits.get(victim);
        if (h == null) {
            return null;
        }
        if (nowMillis - h.timestamp > windowMs) {
            synchronized (stateLock) {
                if (hits.remove(victim, h)) {
                    removeIndex(victim);
                }
            }
            return null;
        }
        return h.attacker;
    }

    /** Convenience overload using the configured default window. */
    public UUID lastPlayerToHit(UUID victim, long nowMillis) {
        return lastPlayerToHit(victim, nowMillis, windowMillis);
    }

    /** Drop the entry for {@code victim} (e.g. on entity death). */
    public void forget(UUID victim) {
        if (victim == null) {
            return;
        }
        synchronized (stateLock) {
            hits.remove(victim);
            removeIndex(victim);
        }
    }

    public int size()            { return hits.size(); }
    public long windowMillis()   { return windowMillis; }
    public int maxEntries()      { return maxEntries; }
    public long evictions()      { return evictions.get(); }

    /** Drop all entries — used on world unload / shutdown. */
    public void clear() {
        synchronized (stateLock) {
            hits.clear();
            byTimestamp.clear();
            indexByVictim.clear();
            evictCounter.set(0L);
        }
    }

    // ------------------------------------------------------------------

    private void evictOldestLocked() {
        int removed = 0;
        while (hits.size() > maxEntries
                && removed < EVICT_STRIDE
                && !byTimestamp.isEmpty()) {
            IndexNode oldest = byTimestamp.pollFirst();
            if (indexByVictim.get(oldest.victim) != oldest) {
                continue;
            }
            indexByVictim.remove(oldest.victim);
            if (hits.remove(oldest.victim, oldest.hit)) {
                removed++;
            }
        }
        if (removed > 0) {
            evictions.addAndGet(removed);
        }
    }

    private void removeIndex(UUID victim) {
        IndexNode old = indexByVictim.remove(victim);
        if (old != null) {
            byTimestamp.remove(old);
        }
    }

    /**
     * Ordered by {@link Hit#timestamp} ascending, then a unique sequence so
     * equal timestamps remain distinct TreeSet entries.
     */
    private static final class IndexNode implements Comparable<IndexNode> {
        final long timestamp;
        final long seq;
        final UUID victim;
        final Hit hit;

        IndexNode(long timestamp, long seq, UUID victim, Hit hit) {
            this.timestamp = timestamp;
            this.seq = seq;
            this.victim = victim;
            this.hit = hit;
        }

        @Override
        public int compareTo(IndexNode o) {
            int byTime = Long.compare(timestamp, o.timestamp);
            return byTime != 0 ? byTime : Long.compare(seq, o.seq);
        }
    }

    private record Hit(UUID attacker, long timestamp) {}
}
