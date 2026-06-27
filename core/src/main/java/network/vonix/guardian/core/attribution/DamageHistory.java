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
            evictOldest();
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
        // Single pass to find the oldest entry. ConcurrentHashMap iteration is weakly
        // consistent — fine for a "drop something" heuristic.
        UUID oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<UUID, Hit> e : hits.entrySet()) {
            long ts = e.getValue().timestamp;
            if (ts < oldestTs) {
                oldestTs = ts;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null && hits.remove(oldestKey) != null) {
            evictions.incrementAndGet();
        }
    }

    private record Hit(UUID attacker, long timestamp) {}
}
