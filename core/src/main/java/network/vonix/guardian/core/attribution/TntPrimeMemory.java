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

    private final ConcurrentHashMap<Key, PrimeRecord> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final int maxEntries;

    /** Current clock supplier — mockable for tests. */
    private final java.util.function.LongSupplier clock;

    /** For amortised eviction: rolling cursor into the entrySet. */
    private volatile java.util.Iterator<java.util.Map.Entry<Key, PrimeRecord>> sweepCursor;

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
            hardEvict(now);
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
        sweepCursor = null;
    }

    /** Amortised sweep: walk up to {@code SWEEP_STRIDE} entries and drop expired. */
    private void maybeEvict(long now) {
        java.util.Iterator<java.util.Map.Entry<Key, PrimeRecord>> it = sweepCursor;
        if (it == null || !it.hasNext()) {
            it = entries.entrySet().iterator();
            sweepCursor = it;
        }
        int n = 0;
        while (it.hasNext() && n < SWEEP_STRIDE) {
            java.util.Map.Entry<Key, PrimeRecord> e = it.next();
            if (now - e.getValue().primedAtMillis > ttlMs) {
                try {
                    it.remove();
                } catch (Throwable ignored) {
                    // ConcurrentModification — drop cursor, next call restarts.
                    sweepCursor = null;
                    return;
                }
            }
            n++;
        }
    }

    /** Hard-evict oldest until size drops below maxEntries. */
    private void hardEvict(long now) {
        // ConcurrentHashMap gives no ordering guarantees, so we just drop
        // any entries older than half-TTL first, then fall back to arbitrary.
        long halfTtlBoundary = now - (ttlMs / 2);
        entries.entrySet().removeIf(e -> e.getValue().primedAtMillis < halfTtlBoundary);
        if (entries.size() <= maxEntries) return;
        // Still over: drop arbitrary entries until under cap.
        java.util.Iterator<java.util.Map.Entry<Key, PrimeRecord>> it = entries.entrySet().iterator();
        while (entries.size() > maxEntries && it.hasNext()) {
            it.next();
            it.remove();
        }
        sweepCursor = null;
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
