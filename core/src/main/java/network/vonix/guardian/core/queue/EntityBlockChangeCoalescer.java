package network.vonix.guardian.core.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Producer-side deduplicator for high-frequency block-change events (typically
 * {@code LivingDestroyBlockEvent} from flying/breathing/spreading modded entities).
 *
 * <p>The bug this exists to prevent: on modpacks like HTTYD (Isle of Berk +
 * Dragons of the Cosmos + 300+ dragon variants), a single dragon flying over
 * unloaded terrain can fire {@code LivingDestroyBlockEvent} 100k+ times per
 * second, per dragon. Multiplied across every dragon in-render, the write
 * queue's producer rate reaches 200k+ actions/sec, overwhelming any reasonable
 * queue capacity or downstream JDBC batch flush.
 *
 * <p>Approach: an LRU-like map keyed by {@code (actorUuid|entityType, worldId,
 * x, y, z)} caches the last-seen timestamp. If a repeat event on the same key
 * arrives within {@link #windowMs} ms, it's skipped. Different actors on the
 * same coord are logged. Different coords by the same actor are logged.
 *
 * <p>The map is bounded ({@link #maxEntries} default 8192) with best-effort
 * eviction: when full, we scan a small tail slice and evict the oldest hits.
 * This is not a strict LRU (would need a doubly-linked list) but is O(1) on
 * the hot path, which is what matters at 200k ops/sec.
 *
 * <p>Thread safety: {@link ConcurrentHashMap} + {@link AtomicLong} — safe for
 * any number of producer threads. In practice all Forge/NeoForge server-thread
 * events fire on a single thread, but Fabric can dispatch off-thread and
 * future MC versions may change this.
 *
 * @since 1.1.4
 */
public final class EntityBlockChangeCoalescer {

    /** Default coalesce window (ms). Empirically, dragons on Berk emit ~5 hits
     *  per block over ~200ms as they cross it. 500ms captures the burst
     *  while still logging distinct destruction of the same block later.  */
    public static final long DEFAULT_WINDOW_MS = 500L;

    /** Default cap on tracked (actor,block) tuples. 8192 * ~64 bytes/entry
     *  ≈ 500KB memory footprint. Enough for hundreds of simultaneous
     *  entity-block interactions. */
    public static final int DEFAULT_MAX_ENTRIES = 8192;

    private final long windowNs;
    private final int maxEntries;
    private final ConcurrentHashMap<Key, AtomicLong> lastSeen;

    // Metrics — read by the queue's histogram so we can see coalescer effectiveness.
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong capDrops = new AtomicLong();
    /**
     * v1.3.1 X6 (P2-3): timestamp (ns) of the most recent sweep that returned zero
     * evictions. During a sustained high-cardinality burst the map is pinned at
     * cap and every fresh key would otherwise pay an O(n) sweep on the tick thread;
     * after a zero-return sweep we short-circuit and drop new events for
     * {@link #ZERO_SWEEP_BACKOFF_NS} before probing again. Bounded amortization —
     * the sweep still runs periodically so eventually-expired entries get cleaned up.
     */
    private final AtomicLong lastZeroSweepNs = new AtomicLong(Long.MIN_VALUE);
    /** Back-off window (ns) after a zero-eviction sweep — 50ms. */
    static final long ZERO_SWEEP_BACKOFF_NS = 50_000_000L;

    public EntityBlockChangeCoalescer() {
        this(DEFAULT_WINDOW_MS, DEFAULT_MAX_ENTRIES);
    }

    public EntityBlockChangeCoalescer(long windowMs, int maxEntries) {
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be > 0");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        this.windowNs = TimeUnit.MILLISECONDS.toNanos(windowMs);
        this.maxEntries = maxEntries;
        this.lastSeen = new ConcurrentHashMap<>(Math.min(maxEntries, 1024));
    }

    /**
     * Decide whether this event should be logged.
     *
     * @param actorId   stable actor identifier — player UUID string, or entity
     *                  type id (e.g. "isleofberk:night_fury"); null-safe
     * @param worldId   world/dimension key (e.g. "minecraft:overworld")
     * @param x         block X
     * @param y         block Y
     * @param z         block Z
     * @return {@code true} if the event should be submitted; {@code false} if
     *         a matching event was seen within the coalesce window
     */
    public boolean shouldLog(String actorId, String worldId, int x, int y, int z) {
        long now = System.nanoTime();
        Key k = new Key(actorId == null ? "" : actorId,
                        worldId == null ? "" : worldId, x, y, z);

        AtomicLong slot = lastSeen.get(k);
        if (slot != null) {
            long prev = slot.get();
            if (now - prev < windowNs) {
                // Within window; refresh timestamp so a sustained hit doesn't
                // let a later event slip through simply because the old
                // timestamp aged out.
                slot.compareAndSet(prev, now);
                hits.incrementAndGet();
                return false;
            }
            // Old entry; update in-place and let this event through.
            slot.set(now);
            misses.incrementAndGet();
            return true;
        }

        // Hard cap before insert. Under high-cardinality bursts (many fresh
        // unique coords inside the coalesce window) old best-effort eviction
        // could remove zero entries and still insert, letting the map grow
        // unbounded. If no slot can be freed, fail closed and drop the new
        // event rather than trading a grief log row for heap pressure.
        if (lastSeen.size() >= maxEntries) {
            // v1.3.1 X6 (P2-3): short-circuit consecutive over-cap inserts. If we just
            // ran a sweep that evicted nothing (map is packed with in-window entries),
            // skip the O(n) scan for ZERO_SWEEP_BACKOFF_NS and drop the event instead.
            // At burst end the timer expires and normal eviction resumes.
            long zeroSweepAt = lastZeroSweepNs.get();
            if (zeroSweepAt != Long.MIN_VALUE && (now - zeroSweepAt) < ZERO_SWEEP_BACKOFF_NS) {
                capDrops.incrementAndGet();
                hits.incrementAndGet();
                return false;
            }
            int evicted = evictOldestSlice(now);
            if (evicted == 0) {
                lastZeroSweepNs.set(now);
                if (lastSeen.size() >= maxEntries) {
                    capDrops.incrementAndGet();
                    hits.incrementAndGet();
                    return false;
                }
            } else {
                // Reset back-off after a productive sweep so a subsequent short burst
                // isn't unfairly rejected.
                lastZeroSweepNs.set(Long.MIN_VALUE);
            }
        }

        AtomicLong prior = lastSeen.putIfAbsent(k, new AtomicLong(now));
        if (prior != null) {
            // Rare race: another producer inserted between our .get and .putIfAbsent.
            // Treat as a hit — the other producer will win, we drop.
            hits.incrementAndGet();
            return false;
        }
        misses.incrementAndGet();
        return true;
    }

    /**
     * Best-effort eviction of the oldest ~1/16 of entries when the map is full.
     * O(n) worst case but only triggered when {@code lastSeen.size() >= maxEntries};
     * at steady state this fires rarely because window-expired entries get
     * overwritten in place by the {@code slot.set(now)} branch above.
     */
    private int evictOldestSlice(long nowNs) {
        int targetEvict = Math.max(1, maxEntries / 16);
        long threshold = nowNs - windowNs;
        int evicted = 0;
        for (var entry : lastSeen.entrySet()) {
            if (evicted >= targetEvict) break;
            if (entry.getValue().get() < threshold) {
                if (lastSeen.remove(entry.getKey(), entry.getValue())) {
                    evicted++;
                }
            }
        }
        evictions.addAndGet(evicted);
        return evicted;
    }

    /** @return count of events suppressed since construction */
    public long hits() { return hits.get(); }

    /** @return count of events allowed through since construction */
    public long misses() { return misses.get(); }

    /** @return count of entries evicted due to cap pressure since construction */
    public long evictions() { return evictions.get(); }

    /** @return count of fresh unique events dropped because the hard cap was reached */
    public long capDrops() { return capDrops.get(); }

    /** @return current live entry count in the coalescer map */
    public int size() { return lastSeen.size(); }

    /** Clear all tracked state. Intended for test use. */
    public void reset() {
        lastSeen.clear();
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        capDrops.set(0);
        lastZeroSweepNs.set(Long.MIN_VALUE);
    }

    /** Composite key. {@code record} gives us equals/hashCode/toString for free. */
    private record Key(String actorId, String worldId, int x, int y, int z) {}
}
