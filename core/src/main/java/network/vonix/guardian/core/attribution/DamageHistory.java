/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.Map;
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
 * <p>Memory is bounded by a configured max entry count with LRU eviction of the
 * oldest victim entry on overflow. Each victim only stores the latest player + ts;
 * this is enough for the "recent damage window" attribution heuristic.
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
     * v1.3.1 X6 (P1-2): counter of insertions observed while {@code size > maxEntries}.
     * We only invoke {@link #evictOldest()} every {@link #EVICT_STRIDE}th over-cap
     * insert, amortizing the O(n) sweep across many events. Between sweeps the map
     * may transiently overshoot the cap by up to {@code EVICT_STRIDE} entries — a
     * negligible heap price for taking the O(n) scan off the server tick.
     */
    private final AtomicLong evictCounter = new AtomicLong();
    /** Amortization stride: run the full oldest-entry sweep every 64th over-cap insert. */
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
        hits.put(victim, new Hit(attacker, timestampMs));
        if (hits.size() > maxEntries) {
            // v1.3.1 X6 (P1-2): amortized eviction. Under sustained combat the map sits
            // at cap and every damage event would otherwise pay an O(n) sweep on the
            // server tick. Only run the sweep every EVICT_STRIDE-th over-cap insert;
            // between sweeps the map overshoots by up to EVICT_STRIDE entries (~1 KB
            // heap), which is trivially cheaper than 63 wasted O(n) scans.
            if ((evictCounter.incrementAndGet() % EVICT_STRIDE) == 0L) {
                evictOldest();
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
            hits.remove(victim, h);
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
        if (victim != null) {
            hits.remove(victim);
        }
    }

    public int size()            { return hits.size(); }
    public long windowMillis()   { return windowMillis; }
    public int maxEntries()      { return maxEntries; }
    public long evictions()      { return evictions.get(); }

    /** Drop all entries — used on world unload / shutdown. */
    public void clear() {
        hits.clear();
    }

    // ------------------------------------------------------------------

    private void evictOldest() {
        // v1.3.1 X6 (P1-2): evict a batch equal to EVICT_STRIDE per amortized sweep.
        // Under sustained cap pressure the record() gate fires the sweep every
        // EVICT_STRIDE-th over-cap insert; each sweep needs to remove at least
        // that many entries to keep the map from growing linearly. We do a
        // single O(n) pass and remove the STRIDE oldest entries in one shot,
        // trading one O(n log STRIDE) sweep every 64 events for O(1) per event
        // amortized (was O(n) per event with only 1 eviction, before X6).
        int target = EVICT_STRIDE;
        // Small ordered ring of "oldest-so-far" keys. Java has no fixed-size
        // priority queue that keeps the MAX for easy replacement; use PQ with
        // reverse ordering so peek() returns the largest of the current picks.
        java.util.PriorityQueue<Map.Entry<UUID, Long>> topOldest =
                new java.util.PriorityQueue<>((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<UUID, Hit> e : hits.entrySet()) {
            long ts = e.getValue().timestamp;
            if (topOldest.size() < target) {
                topOldest.add(Map.entry(e.getKey(), ts));
            } else if (ts < topOldest.peek().getValue()) {
                topOldest.poll();
                topOldest.add(Map.entry(e.getKey(), ts));
            }
        }
        long removed = 0L;
        for (Map.Entry<UUID, Long> e : topOldest) {
            if (hits.remove(e.getKey()) != null) {
                removed++;
            }
        }
        if (removed > 0L) {
            evictions.addAndGet(removed);
        }
    }

    private record Hit(UUID attacker, long timestamp) {}
}
