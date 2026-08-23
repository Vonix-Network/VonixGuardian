/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived cache mapping <em>where an entity just changed a block</em> →
 * <em>which entity did it and whether it was allowlisted</em>, so that fire
 * spawned as a side effect of that entity's action can be paired with (and
 * attributed to) the break — or suppressed when the entity is not allowlisted.
 *
 * <p><strong>The bug this fixes (C2, orphan fire).</strong> On Forge/NeoForge a
 * modded flying griefer (e.g. an {@code isleofberk:*} dragon) destroys blocks
 * via {@link net.minecraft.world.entity.LivingEntity}-driven paths that route
 * through {@code LivingDestroyBlockEvent}. When that entity is <em>not</em> on
 * {@code actions.entityChangeAllowlist} the block break is (correctly) dropped
 * to avoid the {@code AsyncWriteQueue} flood. But the fire that the same action
 * ignites flows through {@code FireBlock} — which is <em>not</em> entity-gated —
 * so the world logs an orphan {@code IGNITE}/{@code BURN} with no actor and no
 * paired break. Rolling that back clears the fire but the destroyed block is
 * already gone from the log, and the un-allowlisted dragon spams fire noise.
 *
 * <p><strong>The fix.</strong> The loader's {@code LivingDestroyBlockEvent}
 * handler is the one place that already knows (a) the causing entity, (b) its
 * resolved attribution, and (c) the {@code VanillaGrieferSet.shouldRecord}
 * allowlist verdict. It records a {@link CauserRecord} here on <em>every</em>
 * entity block change — allowlisted or not — keyed by the break position. When
 * {@code FireBlock} then ignites/burns a block within {@link #DEFAULT_RADIUS}
 * blocks in the next few ticks, the fire bridge {@linkplain #consume consumes}
 * the nearest fresh record and:
 * <ul>
 *   <li><b>allowlisted causer</b> → attribute the fire to that entity and emit
 *       a pairing token ({@link CauserRecord#pairId}) shared with the break so
 *       a rollback restores the block <em>and</em> clears the fire together;</li>
 *   <li><b>non-allowlisted causer</b> → the fire is an orphan side effect of a
 *       creature we deliberately don't audit; the bridge suppresses it;</li>
 *   <li><b>no record</b> → genuine world/player fire; the bridge keeps its
 *       existing {@code #fire} world-source behaviour untouched.</li>
 * </ul>
 *
 * <p><strong>Why a spatial radius (unlike {@link TntPrimeMemory}).</strong> TNT
 * detonates at the exact primed position, so an exact key lookup suffices. Fire
 * lands on a block <em>adjacent</em> to the one the entity broke (the exposed
 * neighbour catches). We therefore scan a small cube around the ignite position
 * and take the freshest hit within {@link #DEFAULT_RADIUS}. The radius is kept
 * tiny (default 2) so we never mis-pair a distant unrelated break.
 *
 * <p><strong>TTL.</strong> Fire from an entity break appears within one or two
 * ticks, occasionally a handful when a chain of neighbours catches. A
 * {@value #DEFAULT_TTL_MS} ms window is generous while still bounding memory.
 *
 * <p><strong>Concurrency / eviction.</strong> {@link ConcurrentHashMap} storage
 * with the same amortised-sweep + gated hard-evict discipline proven in
 * {@link TntPrimeMemory} (v1.3.2 Y5 / v1.3.6 CC2). Map admission, FIFO candidate
 * enqueue, and {@link #clear} of the map/FIFO/counter share {@code evictionLock};
 * the amortised TTL walk uses a separate {@code sweepLock}. Callers never take
 * {@code sweepLock} while holding {@code evictionLock} except {@code hardEvict},
 * which always acquires {@code evictionLock} first. {@link #record} and
 * {@link #consume} are both called from the server thread; the CHM tolerates the
 * occasional off-thread Fabric mixin injection.
 *
 * @since 1.3.10
 */
public final class FireCauserMemory {

    /** Default TTL — fire from an entity break lands within a few ticks. */
    public static final long DEFAULT_TTL_MS = 2_000L;

    /** Default max-entries — a busy modded server can churn many breaks/sec. */
    public static final int DEFAULT_MAX_ENTRIES = 8192;

    /** Default lookup radius (blocks) around the ignite/burn position. */
    public static final int DEFAULT_RADIUS = 2;

    /** Amortised-cleanup stride: how many entries to scan per put/miss. */
    private static final int SWEEP_STRIDE = 32;

    /** Gate on the bounded hard-evict pass. */
    static final int HARD_EVICT_STRIDE = 64;

    /** Per-pass cap on candidate inspections/removals. */
    static final int HARD_EVICT_ARBITRARY_CAP = 128;

    private final ConcurrentHashMap<Key, CauserRecord> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxEntries;
    private final int radius;
    private final java.util.function.LongSupplier clock;

    /**
     * Bounded insertion candidates for hard eviction.  Never scan
     * {@link #entries} wholesale from the server thread: the previous
     * {@code entrySet().removeIf(...)} path made a full-map O(n) sweep visible
     * in watchdog traces during modded-entity floods.
     */
    private final Key[] evictionKeys;
    private final CauserRecord[] evictionRecords;
    private final Object evictionLock = new Object();
    private final int evictionCandidateLimit;
    private int evictionHead;
    private int evictionSize;
    /** Live-map fallback cursor; touched only while holding {@link #evictionLock}. */
    private java.util.Iterator<java.util.Map.Entry<Key, CauserRecord>> fallbackCursor;

    private java.util.Iterator<java.util.Map.Entry<Key, CauserRecord>> sweepCursor;
    private final Object sweepLock = new Object();

    private final java.util.concurrent.atomic.AtomicLong hardEvictCounter =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong hardEvictInvocations =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * Restart-durable pairing-token state. A published {@code AtomicLong}
     * starting at zero reissues {@code 1, 2, 3…} after process restart, and
     * {@code findByPairIds} would then join unrelated historical siblings.
     * Each instance therefore mixes a 64-bit construction nonce with a
     * monotonic sequence through a bijective SplitMix64 avalanche.
     *
     * <p>Assumptions (not cryptographic uniqueness, not absolute uniqueness):
     * <ul>
     *   <li>A generated token is never {@code 0} (the unpaired sentinel).</li>
     *   <li>Within one instance, distinct sequence values produce distinct
     *       tokens because {@link #mix64(long)} is a bijection, until the
     *       sequence wraps at {@code 2^64}.</li>
     *   <li>Two independently constructed instances collide only if their
     *       {@code (nonce + seq)} values collide. The nonce is a nonzero
     *       64-bit {@code ThreadLocalRandom} value; collision probability
     *       is birthday-bound on that nonce, not a timestamp and not a
     *       resettable published counter.</li>
     * </ul>
     */
    private final long pairNonce = newPairNonce();
    private final java.util.concurrent.atomic.AtomicLong pairSeq =
            new java.util.concurrent.atomic.AtomicLong();

    public FireCauserMemory() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_ENTRIES, DEFAULT_RADIUS, System::currentTimeMillis);
    }

    public FireCauserMemory(long ttlMs, int maxEntries, int radius,
                            java.util.function.LongSupplier clock) {
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        if (radius < 0) throw new IllegalArgumentException("radius must be >= 0");
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.radius = radius;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evictionCandidateLimit = maxEntries > Integer.MAX_VALUE - HARD_EVICT_ARBITRARY_CAP
                ? Integer.MAX_VALUE
                : maxEntries + HARD_EVICT_ARBITRARY_CAP;
        this.evictionKeys = new Key[evictionCandidateLimit];
        this.evictionRecords = new CauserRecord[evictionCandidateLimit];
    }

    /**
     * Record the entity that just changed a block at {@code (x,y,z)}.
     *
     * <p>Called from the loader {@code LivingDestroyBlockEvent} handler on
     * <em>every</em> entity block change, allowlisted or not — the
     * {@code allowlisted} flag on the record is what lets the fire bridge decide
     * between pairing and suppression.
     *
     * @param worldId loader-side world id (never {@code null})
     * @param x       break BlockPos X
     * @param y       break BlockPos Y
     * @param z       break BlockPos Z
     * @param rec     the causer record (never {@code null})
     */
    public void record(String worldId, int x, int y, int z, CauserRecord rec) {
        if (worldId == null || rec == null) return;
        long now = clock.getAsLong();
        Key key = new Key(worldId, x, y, z);
        // Callers already stamp with the same clock in production; reuse the
        // immutable record when the timestamp matches to avoid a per-event copy.
        CauserRecord stamped = stampForRecord(rec, now);
        boolean runHardEvict;
        synchronized (evictionLock) {
            entries.put(key, stamped);
            enqueueEvictionCandidate(key, stamped);
            runHardEvict = entries.size() > maxEntries
                    && (hardEvictCounter.incrementAndGet() % HARD_EVICT_STRIDE) == 0L;
        }
        maybeEvict(now);
        if (runHardEvict) {
            hardEvict(now);
        }
    }

    /**
     * Exact-position lookup of a stored pairing token. Used by
     * {@code Guardian.submitEntityChangeBlock} so the break row carries the
     * same {@code pairId} the later fire event will persist.
     *
     * @return the allowlisted pair id at this exact block, or {@code null}
     */
    public Long pairIdAt(String worldId, int x, int y, int z) {
        if (worldId == null) return null;
        CauserRecord r = entries.get(new Key(worldId, x, y, z));
        if (r == null || !r.allowlisted || r.pairId == 0L) return null;
        return r.pairId;
    }

    private CauserRecord stampForRecord(CauserRecord rec, long now) {
        if (!rec.allowlisted) {
            return rec.causedAtMillis == now ? rec : rec.withTimestamp(now);
        }
        long pairId = rec.pairId;
        // Production loaders pass the event timestamp as a provisional pairId
        // (or 0). Allocate a restart-durable token so durable siblings cannot
        // collide across two breaks in the same millisecond *or* across
        // process restart. Explicit nonzero non-timestamp pair ids (42, 77,
        // 99, 100, 200, …) are preserved unchanged.
        if (pairId == 0L || pairId == rec.causedAtMillis) {
            pairId = nextPairId();
        }
        if (pairId == rec.pairId && rec.causedAtMillis == now) {
            return rec;
        }
        return new CauserRecord(rec.actorUuid, rec.actorName, rec.entityKey,
                rec.allowlisted, rec.sourceTagHint, pairId, now);
    }

    /**
     * Cheap, thread-safe generated token. Hot path is an {@code incrementAndGet}
     * plus a handful of arithmetic; no scans, I/O, or shared mutable persistence.
     */
    private long nextPairId() {
        long seq = pairSeq.incrementAndGet();
        long mixed = mix64(pairNonce + seq);
        if (mixed != 0L) {
            return mixed;
        }
        // mix64 is bijective and mix64(0)==0, so pairNonce+seq overflowed to 0.
        mixed = mix64(pairNonce ^ seq);
        return mixed != 0L ? mixed : seq;
    }

    private static long newPairNonce() {
        long n;
        do {
            n = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        } while (n == 0L);
        return n;
    }

    /** SplitMix64 finalizer; bijective on 64-bit values. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /**
     * Consume the freshest non-stale causer record within {@link #radius} of the
     * ignite/burn position, removing it so it pairs with exactly one fire event.
     *
     * @param worldId loader-side world id
     * @param x       ignite/burn BlockPos X
     * @param y       ignite/burn BlockPos Y
     * @param z       ignite/burn BlockPos Z
     * @return the paired causer record, or {@code null} if none is nearby/fresh
     */
    public CauserRecord consume(String worldId, int x, int y, int z) {
        if (worldId == null) return null;
        long now = clock.getAsLong();
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        CauserRecord bestRec = null;
        // One probe key for the whole cube. ConcurrentHashMap.get/remove compare
        // by equals/hashCode and do not retain the argument, so this local is
        // never inserted and never races with a stored key.
        Key probe = new Key(worldId, x, y, z);
        // Small cube scan. radius is tiny (default 2 → 125 probes worst case) and
        // most positions miss, so this stays cheap on the server tick.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    probe.set(worldId, x + dx, y + dy, z + dz);
                    CauserRecord r = entries.get(probe);
                    if (r == null) continue;
                    if (now - r.causedAtMillis > ttlMs) {
                        entries.remove(probe, r);
                        continue;
                    }
                    if (bestRec == null || r.causedAtMillis > bestRec.causedAtMillis) {
                        bestX = x + dx;
                        bestY = y + dy;
                        bestZ = z + dz;
                        bestRec = r;
                    }
                }
            }
        }
        if (bestRec != null) {
            entries.remove(probe.set(worldId, bestX, bestY, bestZ), bestRec);
        } else {
            maybeEvict(now);
        }
        return bestRec;
    }

    /**
     * Peek at the freshest nearby record without consuming it. Reserved for
     * chained-neighbour fire where one break ignites several adjacent blocks.
     */
    public CauserRecord peek(String worldId, int x, int y, int z) {
        if (worldId == null) return null;
        long now = clock.getAsLong();
        CauserRecord bestRec = null;
        Key probe = new Key(worldId, x, y, z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    CauserRecord r = entries.get(probe.set(worldId, x + dx, y + dy, z + dz));
                    if (r == null || now - r.causedAtMillis > ttlMs) continue;
                    if (bestRec == null || r.causedAtMillis > bestRec.causedAtMillis) {
                        bestRec = r;
                    }
                }
            }
        }
        return bestRec;
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        synchronized (evictionLock) {
            entries.clear();
            java.util.Arrays.fill(evictionKeys, null);
            java.util.Arrays.fill(evictionRecords, null);
            evictionHead = 0;
            evictionSize = 0;
            fallbackCursor = null;
            hardEvictCounter.set(0L);
        }
        synchronized (sweepLock) {
            sweepCursor = null;
        }
    }

    long hardEvictInvocations() {
        return hardEvictInvocations.get();
    }

    private void maybeEvict(long now) {
        synchronized (sweepLock) {
            java.util.Iterator<java.util.Map.Entry<Key, CauserRecord>> it = sweepCursor;
            if (it == null || !it.hasNext()) {
                it = entries.entrySet().iterator();
                sweepCursor = it;
            }
            int n = 0;
            while (it.hasNext() && n < SWEEP_STRIDE) {
                java.util.Map.Entry<Key, CauserRecord> e;
                try {
                    e = it.next();
                } catch (java.util.ConcurrentModificationException cme) {
                    sweepCursor = null;
                    return;
                }
                if (now - e.getValue().causedAtMillis > ttlMs) {
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
     * Remove expired/over-cap entries using a bounded candidate deque, then a
     * bounded live-map fallback if the deque made no progress or is empty
     * while still over cap. This deliberately does not call
     * {@code entrySet().removeIf}: that operation is an unbounded O(n)
     * server-thread sweep and was the direct cause of the watchdog stalls
     * reported against Forge 1.3.10.
     */
    private void hardEvict(long now) {
        hardEvictInvocations.incrementAndGet();
        long halfTtlBoundary = now - (ttlMs / 2);
        synchronized (evictionLock) {
            int inspected = 0;
            while (entries.size() > maxEntries
                    && inspected < HARD_EVICT_ARBITRARY_CAP
                    && evictionSize > 0) {
                Key candidateKey = evictionKeys[evictionHead];
                CauserRecord candidateRecord = evictionRecords[evictionHead];
                evictionKeys[evictionHead] = null;
                evictionRecords[evictionHead] = null;
                evictionHead = (evictionHead + 1) % evictionCandidateLimit;
                evictionSize--;
                inspected++;
                CauserRecord current = entries.get(candidateKey);
                if (current != candidateRecord) {
                    continue;
                }
                // If the cache is over cap, removing a current young candidate
                // is still preferable to exceeding the bound. Old candidates
                // are naturally removed first because the deque is FIFO.
                if (current.causedAtMillis < halfTtlBoundary || entries.size() > maxEntries) {
                    entries.remove(candidateKey, current);
                }
            }
            if (entries.size() > maxEntries) {
                evictLiveFallback(now, halfTtlBoundary);
            }
        }
        synchronized (sweepLock) {
            sweepCursor = null;
        }
    }

    /**
     * Bounded live-map fallback. Caller holds {@link #evictionLock}.
     * Prefers expired/half-TTL-stale entries, then drops any live identity
     * match while over cap. Budget is {@link #HARD_EVICT_ARBITRARY_CAP}.
     */
    private void evictLiveFallback(long now, long halfTtlBoundary) {
        int inspected = 0;
        while (entries.size() > maxEntries && inspected < HARD_EVICT_ARBITRARY_CAP) {
            java.util.Iterator<java.util.Map.Entry<Key, CauserRecord>> it = fallbackCursor;
            if (it == null || !it.hasNext()) {
                it = entries.entrySet().iterator();
                fallbackCursor = it;
                if (!it.hasNext()) {
                    return;
                }
            }
            java.util.Map.Entry<Key, CauserRecord> e;
            try {
                e = it.next();
            } catch (java.util.ConcurrentModificationException cme) {
                fallbackCursor = null;
                inspected++;
                continue;
            }
            inspected++;
            Key key = e.getKey();
            CauserRecord candidate = e.getValue();
            CauserRecord current = entries.get(key);
            if (current != candidate) {
                continue;
            }
            if (now - current.causedAtMillis > ttlMs
                    || current.causedAtMillis < halfTtlBoundary
                    || entries.size() > maxEntries) {
                entries.remove(key, current);
            }
        }
    }

    /** Caller must hold {@link #evictionLock}. */
    private void enqueueEvictionCandidate(Key key, CauserRecord record) {
        int tail = (evictionHead + evictionSize) % evictionCandidateLimit;
        if (evictionSize == evictionCandidateLimit) {
            evictionKeys[evictionHead] = null;
            evictionRecords[evictionHead] = null;
            evictionHead = (evictionHead + 1) % evictionCandidateLimit;
            evictionSize--;
        }
        evictionKeys[tail] = key;
        evictionRecords[tail] = record;
        evictionSize++;
    }

    /**
     * Composite key: world id + BlockPos. Stored instances are never mutated
     * after {@link #record}; consume/peek reuse a stack-local probe that is
     * never inserted into {@link #entries}.
     */
    private static final class Key {
        private String worldId;
        private int x;
        private int y;
        private int z;
        private int hash;

        private Key(String worldId, int x, int y, int z) {
            set(worldId, x, y, z);
        }

        private Key set(String worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            int h = worldId.hashCode();
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            this.hash = h;
            return this;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return x == k.x && y == k.y && z == k.z && worldId.equals(k.worldId);
        }
    }

    /**
     * Immutable record of the entity that changed a block — carries the actor
     * attribution, the allowlist verdict, a source-tag hint, and a
     * {@link #pairId} used to couple the eventual fire event with this break for
     * a joined rollback.
     */
    public static final class CauserRecord {
        /** Resolved actor UUID (may be {@code null} for a pure mob). */
        public final UUID actorUuid;
        /** Resolved actor display name (never {@code null}). */
        public final String actorName;
        /** Entity sentinel/key of the causing entity (e.g. {@code isleofberk:lightfury}). */
        public final String entityKey;
        /** Whether {@code VanillaGrieferSet.shouldRecord} accepted this entity. */
        public final boolean allowlisted;
        /** Source-tag hint carried onto the paired fire event (e.g. {@code #entity}). */
        public final String sourceTagHint;
        /**
         * Correlation id shared by the break and its paired fire event.
         * {@code 0} is unpaired. Generated tokens are restart-durable (not a
         * process-local sequence published as the persisted value).
         */
        public final long pairId;
        public final long causedAtMillis;

        public CauserRecord(UUID actorUuid, String actorName, String entityKey,
                            boolean allowlisted, String sourceTagHint,
                            long pairId, long causedAtMillis) {
            this.actorUuid = actorUuid;
            this.actorName = actorName != null ? actorName : "#entity";
            this.entityKey = entityKey;
            this.allowlisted = allowlisted;
            this.sourceTagHint = sourceTagHint != null ? sourceTagHint : "#entity";
            this.pairId = pairId;
            this.causedAtMillis = causedAtMillis;
        }

        /** Factory for an allowlisted, attributed causer (fire will be paired). */
        public static CauserRecord allowlisted(UUID uuid, String name, String entityKey,
                                               String sourceTag, long pairId, long now) {
            return new CauserRecord(uuid, name, entityKey, true,
                    sourceTag != null ? sourceTag : "#entity", pairId, now);
        }

        /** Factory for a non-allowlisted causer (fire will be suppressed as orphan noise). */
        public static CauserRecord suppressed(String entityKey, long now) {
            return new CauserRecord(null, "#entity", entityKey, false, "#entity", 0L, now);
        }

        CauserRecord withTimestamp(long now) {
            return new CauserRecord(actorUuid, actorName, entityKey, allowlisted,
                    sourceTagHint, pairId, now);
        }

        @Override public String toString() {
            return "CauserRecord{" + actorName + "/" + entityKey
                    + "/allow=" + allowlisted + "/pair=" + pairId + "}";
        }
    }
}
