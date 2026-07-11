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
 * <p><strong>Concurrency / eviction.</strong> Lock-free {@link ConcurrentHashMap}
 * storage with the same amortised-sweep + gated hard-evict discipline proven in
 * {@link TntPrimeMemory} (v1.3.2 Y5 / v1.3.6 CC2). {@link #record} and
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

    /** Gate on the O(n) hard-evict sweep (mirrors {@link TntPrimeMemory}). */
    static final int HARD_EVICT_STRIDE = 64;

    /** Per-sweep cap on arbitrary evictions (mirrors {@link TntPrimeMemory}). */
    static final int HARD_EVICT_ARBITRARY_CAP = 128;

    private final ConcurrentHashMap<Key, CauserRecord> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxEntries;
    private final int radius;
    private final java.util.function.LongSupplier clock;

    private java.util.Iterator<java.util.Map.Entry<Key, CauserRecord>> sweepCursor;
    private final Object sweepLock = new Object();

    private final java.util.concurrent.atomic.AtomicLong hardEvictCounter =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong hardEvictInvocations =
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
        entries.put(new Key(worldId, x, y, z), rec.withTimestamp(now));
        maybeEvict(now);
        if (entries.size() > maxEntries
                && (hardEvictCounter.incrementAndGet() % HARD_EVICT_STRIDE) == 0L) {
            hardEvict(now);
        }
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
        Key best = null;
        CauserRecord bestRec = null;
        // Small cube scan. radius is tiny (default 2 → 125 keys worst case) and
        // most positions miss, so this stays cheap on the server tick.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Key k = new Key(worldId, x + dx, y + dy, z + dz);
                    CauserRecord r = entries.get(k);
                    if (r == null) continue;
                    if (now - r.causedAtMillis > ttlMs) {
                        entries.remove(k, r);
                        continue;
                    }
                    if (bestRec == null || r.causedAtMillis > bestRec.causedAtMillis) {
                        best = k;
                        bestRec = r;
                    }
                }
            }
        }
        if (best != null) {
            entries.remove(best, bestRec);
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
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    CauserRecord r = entries.get(new Key(worldId, x + dx, y + dy, z + dz));
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
        entries.clear();
        synchronized (sweepLock) {
            sweepCursor = null;
        }
        hardEvictCounter.set(0L);
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

    private void hardEvict(long now) {
        hardEvictInvocations.incrementAndGet();
        long halfTtlBoundary = now - (ttlMs / 2);
        entries.entrySet().removeIf(e -> e.getValue().causedAtMillis < halfTtlBoundary);
        if (entries.size() <= maxEntries) return;
        java.util.Iterator<java.util.Map.Entry<Key, CauserRecord>> it = entries.entrySet().iterator();
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

    /** Composite key: world id + BlockPos. */
    private record Key(String worldId, int x, int y, int z) { }

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
        /** Correlation id shared by the break and its paired fire event. */
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
