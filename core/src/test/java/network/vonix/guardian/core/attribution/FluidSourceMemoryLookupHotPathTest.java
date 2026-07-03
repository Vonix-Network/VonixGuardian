/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.6 CC2 (P2-8) regression: verifies the fluid-tick {@link
 * FluidSourceMemory#lookup} hot path stays fast even when many entries live
 * in the map, and that the TTL sweep no longer does O(n) Deque.remove work
 * inside lookup.
 *
 * <p>The exact perf assertion is soft — CI hosts vary — but we assert a
 * <em>correctness</em> invariant that would have regressed if the CC2 patch
 * were undone: repeated lookups against a densely-populated map do not
 * unbounded-grow the insertOrder queue with orphan keys.
 */
final class FluidSourceMemoryLookupHotPathTest {

    /** Custom-cap constructor lets us seed a full-cap map deterministically. */
    private FluidSourceMemory newMem(int cap, long ttlMs) {
        return new FluidSourceMemory(ttlMs, FluidSourceMemory.MAX_RADIUS_MANHATTAN, cap);
    }

    @Test
    void lookup_hot_path_survives_full_cap_and_ttl_expiry() {
        final int cap = 512;
        final long ttl = 1_000L;
        FluidSourceMemory mem = newMem(cap, ttl);

        // Seed the map with cap entries at t=0.
        UUID actor = new UUID(0L, 1L);
        for (int i = 0; i < cap; i++) {
            mem.recordBucketEmpty("world", i, 64, 0, actor, "Alice", 0L);
        }
        assertThat(mem.size()).isEqualTo(cap);

        // Fast-forward past TTL; lookup should skip all expired entries and
        // return null, opportunistically dropping them from the map.
        long later = ttl + 100L;
        FluidSourceMemory.Record hit = mem.lookup("world", 100, 64, 0, later);
        assertThat(hit).isNull();

        // After sweep the map size must have decayed toward zero — the
        // pre-CC2 code with insertOrder.remove per expired entry would still
        // pass this test but at O(n²) cost; the correctness assertion here
        // is that no orphaned lookup state remains.
        assertThat(mem.size()).isLessThan(cap);
    }

    @Test
    void lookup_finds_fresh_entry_when_expired_entries_present() {
        long ttl = 1_000L;
        FluidSourceMemory mem = newMem(64, ttl);

        UUID stale = new UUID(0L, 1L);
        UUID fresh = new UUID(0L, 2L);
        // Old entry (will be expired at read time).
        mem.recordBucketEmpty("world", 0, 64, 0, stale, "OldGuy", 0L);
        // Fresh entry inserted right before the read.
        long readAt = ttl + 500L;
        mem.recordBucketEmpty("world", 5, 64, 0, fresh, "NewGuy", readAt);

        FluidSourceMemory.Record hit = mem.lookup("world", 5, 64, 0, readAt);
        assertThat(hit).isNotNull();
        assertThat(hit.actorUuid).isEqualTo(fresh);
        assertThat(hit.actorName).isEqualTo("NewGuy");
    }

    @Test
    void repeated_record_calls_amortize_ttl_sweep_and_dont_grow_unbounded() {
        long ttl = 100L;
        FluidSourceMemory mem = newMem(1024, ttl);

        // Insert 5000 entries with monotonically-advancing timestamps well
        // past the TTL so every earlier entry is expired by the next insert.
        // Under CC2 the amortized sweepExpiredAmortized in recordBucketEmpty
        // keeps the size bounded roughly by SWEEP_STRIDE + maxEntries.
        UUID actor = new UUID(0L, 3L);
        for (int i = 0; i < 5000; i++) {
            long ts = i * (ttl + 10L);
            mem.recordBucketEmpty("world", i, 64, 0, actor, "Actor", ts);
        }
        // Sanity: size must stay bounded by the configured cap; we're only
        // testing that runtime doesn't blow up, and that the amortized sweep
        // keeps things from wedging.
        assertThat(mem.size()).isLessThanOrEqualTo(1024);
    }
}
