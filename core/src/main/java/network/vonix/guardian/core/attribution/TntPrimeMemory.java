/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived cache mapping <em>where TNT was primed</em> → <em>who primed it</em>.
 *
 * <p>Fixes CoreProtect-parity gap G-CP-2. Vanilla Minecraft has no
 * {@code TNTPrimeEvent}; when a {@link net.minecraft.world.entity.item.PrimedTnt}
 * eventually explodes, {@code ExplosionEvent.Detonate} only exposes the
 * {@code PrimedTnt} entity itself as the direct source. That erases the actor
 * chain — dispenser dropping flint&amp;steel, redstone-charged
 * {@code TntBlock.onCaughtFire}, spreading fire, or a player right-clicking
 * with flint&amp;steel all collapse to sentinel {@code #tnt}.
 *
 * <p>The loader-side mixin surface (X7) records the actor at the moment the
 * TNT block is primed — either just before it converts to a
 * {@code PrimedTnt} entity (block-level {@code onCaughtFire} hook) or in the
 * {@code PrimedTnt} constructor when the {@code LivingEntity igniter} carries
 * a player. The record is keyed by {@code (worldId, packedBlockPos)} so the
 * same-tick or next-fuse-tick explosion can look it up in
 * {@link network.vonix.guardian.core.attribution.UniversalAttribution#resolveTntPrime}.
 *
 * <p>The map is bounded implicitly by:
 * <ul>
 *   <li>A <strong>5-minute TTL</strong> that survives the maximum
 *       fuse extensions we've seen in modpacks (vanilla fuse = 4 s;
 *       {@code Create}, {@code Immersive Engineering} and various QoL mods can
 *       extend fuses to minutes).</li>
 *   <li>Amortised eviction on every put and on every lookup miss
 *       (walk a bounded prefix of entries per call).</li>
 *   <li>A hard cap on entries: over-cap puts trigger an unconditional
 *       oldest-entry sweep before insert.</li>
 * </ul>
 *
 * <p>All operations are lock-free — {@link ConcurrentHashMap} is the storage,
 * and record timestamps are read via {@code entry.getValue().primedAtMillis}
 * without additional synchronisation because {@link PrimeRecord} is immutable.
 *
 * <p>Thread-model: {@link #record} is called from the server thread (mixin on
 * {@code onCaughtFire} / {@code PrimedTnt.&lt;init&gt;}). {@link #consume} is
 * called from the server thread inside the {@code ExplosionEvent.Detonate}
 * handler. Both may be called concurrently on Fabric worlds when off-thread
 * mixin injections occur; {@link ConcurrentHashMap} handles that.
 *
 * @since 1.3.1
 */
public final class TntPrimeMemory {

    /** Default TTL — matches the outer bound of "reasonable" mod-extended fuses. */
    public static final long DEFAULT_TTL_MS = 5L * 60L * 1000L;

    /** Default max-entries — sufficient for a busy server (thousands of TNT/min). */
    public static final int DEFAULT_MAX_ENTRIES = 8192;

    /** Amortised-cleanup stride: how many entries to scan per put/miss. */
    private static final int SWEEP_STRIDE = 32;

    /**
     * v1.3.2 Y5 (P1-3): amortization stride for {@link #hardEvict(long)}. Under
     * sustained over-cap pressure (busy modded server priming thousands of TNT
     * per minute) X7 would otherwise pay a full O(n) scan on every over-cap
     * insert on the server thread. Mirrors the {@code DamageHistory.EVICT_STRIDE}
     * pattern introduced in X6: only invoke the real sweep every 64th over-cap
     * insert. Between sweeps the map may transiently overshoot the cap by up
     * to {@code HARD_EVICT_STRIDE} entries (~9 KiB heap for {@link PrimeRecord})
     * — trivially cheaper than 63 wasted O(n) scans on the tick.
     */
    static final int HARD_EVICT_STRIDE = 64;

    /**
     * v1.3.2 Y5 (P1-3): cap on the second-pass "drop arbitrary entries" loop
     * inside {@link #hardEvict(long)}. When the halfTtl removeIf pass fails to
     * bring us under cap (all entries young), we bound the number of arbitrary
     * evictions to this many per sweep. Keeps the amortized cost of a single
     * hardEvict call independent of {@code maxEntries}. Chosen so that at
     * {@link #HARD_EVICT_STRIDE} = 64 amortization we can absorb an insert
     * storm of {@code 128 * 64 = 8192} entries before size actually catches
     * back up to cap — matches the default {@link #DEFAULT_MAX_ENTRIES}.
     */
    static final int HARD_EVICT_ARBITRARY_CAP = 128;

    private final ConcurrentHashMap<Key, PrimeRecord> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxEntries;

    /** Current clock supplier — mockable for tests. */
    private final java.util.function.LongSupplier clock;

    /**
     * For amortised eviction: cursor is guarded by {@link #sweepLock} to
     * avoid the pre-1.3.6 CME race where two concurrent {@link #record}
     * callers could share a single iterator and blow up on
     * {@code it.next()}. The lock is small-scope (SWEEP_STRIDE entries) and
     * held only during eviction, not during record/consume/peek.
     *
     * <p>v1.3.6 CC2 (P2-9): dropped the {@code volatile} qualifier — a
     * volatile-published iterator is unsafe to share across threads even
     * with a happens-before edge because {@link java.util.Iterator} itself
     * is not thread-safe and its internal expectedModCount state can go
     * stale mid-sweep.
     */
    private java.util.Iterator<java.util.Map.Entry<Key, PrimeRecord>> sweepCursor;
    private final Object sweepLock = new Object();

    /**
     * v1.3.2 Y5 (P1-3): counter of insertions observed while {@code size > maxEntries}.
     * We only invoke the real {@link #hardEvict(long)} sweep every
     * {@link #HARD_EVICT_STRIDE}th over-cap insert. Test-visible via
     * {@link #hardEvictInvocations()}.
     */
    private final java.util.concurrent.atomic.AtomicLong hardEvictCounter =
            new java.util.concurrent.atomic.AtomicLong();

    /** Test-visible counter of times the real hardEvict sweep actually ran. */
    private final java.util.concurrent.atomic.AtomicLong hardEvictInvocations =
            new java.util.concurrent.atomic.AtomicLong();

    public TntPrimeMemory() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    public TntPrimeMemory(long ttlMs, int maxEntries, java.util.function.LongSupplier clock) {
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Record the actor responsible for priming a TNT block.
     *
     * @param worldId  loader-side world id (never {@code null})
     * @param x        prime BlockPos X
     * @param y        prime BlockPos Y
     * @param z        prime BlockPos Z
     * @param actor    responsible actor; may be {@code null} for natural
     *                 (fire spread without a known lighter) — recorded so the
     *                 detonate handler can still see the source-tag hint
     */
    public void record(String worldId, int x, int y, int z, PrimeRecord actor) {
        if (worldId == null || actor == null) return;
        long now = clock.getAsLong();
        Key k = new Key(worldId, x, y, z);
        entries.put(k, actor.withTimestamp(now));
        maybeEvict(now);
        if (entries.size() > maxEntries) {
            // v1.3.2 Y5 (P1-3): amortize the O(n) hard-evict sweep. Under a
            // sustained over-cap prime storm (thousands of TNT/min on a modded
            // server), invoking hardEvict on every record() call put an O(n)
            // scan on the server tick. Only fire the real sweep every
            // HARD_EVICT_STRIDE-th over-cap insert; between sweeps the map
            // may transiently overshoot by up to STRIDE entries.
            if ((hardEvictCounter.incrementAndGet() % HARD_EVICT_STRIDE) == 0L) {
                hardEvict(now);
            }
        }
    }

    /**
     * Consume the actor recorded for this priming position.
     *
     * <p>Returns {@code null} if no record exists, the record is stale, or
     * the position wasn't primed by a tracked source. The record is
     * <strong>removed</strong> on hit to bound memory across long-lived worlds.
     *
     * @param worldId loader-side world id
     * @param x       explosion origin BlockPos X (typically {@code entity.blockPosition()})
     * @param y       explosion origin BlockPos Y
     * @param z       explosion origin BlockPos Z
     * @return the recorded prime, or {@code null}
     */
    public PrimeRecord consume(String worldId, int x, int y, int z) {
        if (worldId == null) return null;
        Key k = new Key(worldId, x, y, z);
        PrimeRecord r = entries.remove(k);
        long now = clock.getAsLong();
        if (r == null) {
            maybeEvict(now);
            return null;
        }
        if (now - r.primedAtMillis > ttlMs) {
            return null;
        }
        return r;
    }

    /**
     * Peek without removing (used by the resolver-side "look but keep for
     * chained-explosion case" branch, which is not yet wired but reserved).
     *
     * @param worldId loader-side world id
     * @param x       BlockPos X
     * @param y       BlockPos Y
     * @param z       BlockPos Z
     * @return the recorded prime, or {@code null}
     */
    public PrimeRecord peek(String worldId, int x, int y, int z) {
        if (worldId == null) return null;
        PrimeRecord r = entries.get(new Key(worldId, x, y, z));
        if (r == null) return null;
        long now = clock.getAsLong();
        if (now - r.primedAtMillis > ttlMs) {
            entries.remove(new Key(worldId, x, y, z), r);
            return null;
        }
        return r;
    }

    /** Current entry count (test/diagnostic hook). */
    public int size() {
        return entries.size();
    }

    /** Fully clear the cache. Used on shutdown/reload. */
    public void clear() {
        entries.clear();
        synchronized (sweepLock) {
            sweepCursor = null;
        }
        hardEvictCounter.set(0L);
    }

    /** Test-visible: number of times the real hardEvict sweep actually ran (Y5). */
    long hardEvictInvocations() {
        return hardEvictInvocations.get();
    }

    /** Amortised sweep: walk up to {@code SWEEP_STRIDE} entries and drop expired. */
    private void maybeEvict(long now) {
        // v1.3.6 CC2 (P2-9): sweep under sweepLock — the previous volatile
        // shared iterator was CME-racy when two concurrent callers (server
        // thread + off-thread mixin injection) landed inside maybeEvict at
        // once. The lock is only held for at most SWEEP_STRIDE entries so it
        // never contends with the hot record/consume path.
        synchronized (sweepLock) {
            java.util.Iterator<java.util.Map.Entry<Key, PrimeRecord>> it = sweepCursor;
            if (it == null || !it.hasNext()) {
                it = entries.entrySet().iterator();
                sweepCursor = it;
            }
            int n = 0;
            while (it.hasNext() && n < SWEEP_STRIDE) {
                java.util.Map.Entry<Key, PrimeRecord> e;
                try {
                    e = it.next();
                } catch (java.util.ConcurrentModificationException cme) {
                    // Another mutation raced this cursor even under lock (e.g.
                    // a putIfAbsent on ConcurrentHashMap invalidated our fail-
                    // safe iterator's expectedModCount view). Drop and retry
                    // on the next tick — safe under-sweep.
                    sweepCursor = null;
                    return;
                }
                if (now - e.getValue().primedAtMillis > ttlMs) {
                    try {
                        it.remove();
                    } catch (Throwable ignored) {
                        sweepCursor = null;
                        return;
                    }
                }
                n++;
            }
        }
    }

    /**
     * Hard-evict oldest until size drops below maxEntries.
     *
     * <p>v1.3.2 Y5 (P1-3): callers gate this via
     * {@link #hardEvictCounter} % {@link #HARD_EVICT_STRIDE} so this method
     * only runs on every 64th over-cap insert. The internal "drop arbitrary
     * entries" second-pass loop is now bounded by
     * {@link #HARD_EVICT_ARBITRARY_CAP} iterations to keep amortized cost
     * per hardEvict call independent of {@code maxEntries}.
     */
    private void hardEvict(long now) {
        hardEvictInvocations.incrementAndGet();
        // ConcurrentHashMap gives no ordering guarantees, so we just drop
        // any entries older than half-TTL first, then fall back to arbitrary.
        long halfTtlBoundary = now - (ttlMs / 2);
        entries.entrySet().removeIf(e -> e.getValue().primedAtMillis < halfTtlBoundary);
        if (entries.size() <= maxEntries) return;
        // Still over: drop arbitrary entries until under cap OR we hit the
        // per-sweep cap. Bounding this loop keeps a single hardEvict call at
        // O(min(overshoot, HARD_EVICT_ARBITRARY_CAP)) rather than O(overshoot).
        // Combined with the STRIDE gate on the caller, worst-case amortized
        // work per record() call is O(HARD_EVICT_ARBITRARY_CAP / HARD_EVICT_STRIDE).
        java.util.Iterator<java.util.Map.Entry<Key, PrimeRecord>> it = entries.entrySet().iterator();
        int dropped = 0;
        while (entries.size() > maxEntries && it.hasNext() && dropped < HARD_EVICT_ARBITRARY_CAP) {
            it.next();
            it.remove();
            dropped++;
        }
        synchronized (sweepLock) {
            sweepCursor = null;
        }
    }

    /**
     * Composite key: world id + packed BlockPos. Interned by
     * {@code ConcurrentHashMap} equality.
     */
    private record Key(String worldId, int x, int y, int z) { }

    /**
     * Immutable prime record — carries who primed the block, when, and
     * a source-tag hint for {@link SourceTaggerHint} classification.
     */
    public static final class PrimeRecord {
        public final UUID actorUuid;
        public final String actorName;
        /** Loader-side attribution kind at the moment of priming — usually PLAYER_DIRECT. */
        public final AttributionKind kind;
        /** Source tag hint: e.g. {@code "#dispenser"}, {@code "#fire"}, {@code "#redstone"}, {@code "#player"}. */
        public final String sourceTagHint;
        public final long primedAtMillis;

        public PrimeRecord(UUID actorUuid, String actorName, AttributionKind kind,
                           String sourceTagHint, long primedAtMillis) {
            this.actorUuid = actorUuid;
            this.actorName = actorName;
            this.kind = kind != null ? kind : AttributionKind.UNKNOWN;
            this.sourceTagHint = sourceTagHint != null ? sourceTagHint : "#tnt";
            this.primedAtMillis = primedAtMillis;
        }

        /**
         * Convenience factory for a player-primed record (right-click flint&amp;steel).
         */
        public static PrimeRecord player(UUID uuid, String name, long now) {
            return new PrimeRecord(uuid, name, AttributionKind.PLAYER_DIRECT, "#player", now);
        }

        /**
         * Convenience factory for a dispenser-primed record (flint&amp;steel dispensed).
         * Actor may be null — dispensers rarely track a placer we can reach.
         */
        public static PrimeRecord dispenser(UUID actorUuid, String actorName, long now) {
            return new PrimeRecord(actorUuid, actorName,
                    actorUuid != null ? AttributionKind.PLAYER_INDIRECT : AttributionKind.UNKNOWN,
                    "#dispenser", now);
        }

        /** Convenience factory for a redstone-primed record. */
        public static PrimeRecord redstone(UUID actorUuid, String actorName, long now) {
            return new PrimeRecord(actorUuid, actorName,
                    actorUuid != null ? AttributionKind.PLAYER_INDIRECT : AttributionKind.UNKNOWN,
                    "#redstone", now);
        }

        /** Convenience factory for a fire-spread-primed record. */
        public static PrimeRecord fire(UUID actorUuid, String actorName, long now) {
            return new PrimeRecord(actorUuid, actorName,
                    actorUuid != null ? AttributionKind.PLAYER_INDIRECT : AttributionKind.UNKNOWN,
                    "#fire", now);
        }

        /** Rebuild with a fresh timestamp (used on put). */
        PrimeRecord withTimestamp(long now) {
            return new PrimeRecord(actorUuid, actorName, kind, sourceTagHint, now);
        }

        @Override public String toString() {
            return "PrimeRecord{" + actorName + "/" + kind + "/" + sourceTagHint + "}";
        }
    }

    /**
     * Marker only — separates the tagging vocabulary from the concrete
     * source-tag strings the loader-side SourceTagger uses. Kept close to
     * the record so future refactors surface here.
     */
    private interface SourceTaggerHint { }
}
