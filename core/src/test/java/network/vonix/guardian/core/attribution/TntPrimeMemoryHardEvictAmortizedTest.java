/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.2 Y5 (P1-3) — regression tests for the amortized
 * {@link TntPrimeMemory#record(String, int, int, int, TntPrimeMemory.PrimeRecord)
 * record()} → {@code hardEvict()} path.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Under-cap inserts never fire the hard-evict sweep.</li>
 *   <li>Over-cap insert storm only invokes the real hardEvict sweep every
 *       {@link TntPrimeMemory#HARD_EVICT_STRIDE}th insert (amortization).</li>
 *   <li>The map stays bounded near {@code cap + HARD_EVICT_STRIDE +
 *       HARD_EVICT_ARBITRARY_CAP} even under a large storm — the second-pass
 *       loop is capped and correctness of the bound holds.</li>
 *   <li>Stride constant hasn't drifted.</li>
 * </ul>
 */
class TntPrimeMemoryHardEvictAmortizedTest {

    private static final String WORLD = "minecraft:overworld";
    private static final long TTL = 60_000L;

    @Test
    void stride_matches_documented_constant() {
        assertThat(TntPrimeMemory.HARD_EVICT_STRIDE).isEqualTo(64);
    }

    @Test
    void arbitrary_cap_matches_documented_constant() {
        assertThat(TntPrimeMemory.HARD_EVICT_ARBITRARY_CAP).isEqualTo(128);
    }

    @Test
    void under_cap_never_triggers_hard_evict() {
        int cap = 64;
        AtomicLong now = new AtomicLong(1_000L);
        TntPrimeMemory mem = new TntPrimeMemory(TTL, cap, now::get);
        for (int i = 0; i < cap; i++) {
            mem.record(WORLD, i, 64, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "P" + i, now.get()));
        }
        assertThat(mem.size()).isEqualTo(cap);
        assertThat(mem.hardEvictInvocations()).isZero();
    }

    @Test
    void over_cap_only_fires_hard_evict_every_stride_calls() {
        int cap = 128;
        AtomicLong now = new AtomicLong(1_000L);
        TntPrimeMemory mem = new TntPrimeMemory(TTL, cap, now::get);
        // Push exactly cap + STRIDE distinct entries at distinct positions.
        // The first `cap` puts stay under cap → 0 hardEvict.
        // The next STRIDE puts are over-cap → exactly ONE hardEvict when the
        // counter first hits STRIDE (the STRIDE-th over-cap insert).
        int inserts = cap + TntPrimeMemory.HARD_EVICT_STRIDE;
        for (int i = 0; i < inserts; i++) {
            mem.record(WORLD, i, 64, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "P" + i, now.get()));
        }
        assertThat(mem.hardEvictInvocations())
                .as("hardEvict should fire exactly once when over-cap counter hits STRIDE")
                .isEqualTo(1L);
    }

    @Test
    void insert_storm_stays_bounded_and_amortizes() {
        int cap = 256;
        AtomicLong now = new AtomicLong(1_000L);
        TntPrimeMemory mem = new TntPrimeMemory(TTL, cap, now::get);
        // 20x cap distinct entries — an aggressive prime-storm scenario.
        int inserts = cap * 20;
        for (int i = 0; i < inserts; i++) {
            mem.record(WORLD, i, 64, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "P" + i, now.get()));
        }
        // Steady-state bound: cap + one full STRIDE window + one full
        // arbitrary-cap sweep worth of headroom. The half-TTL branch cannot
        // evict here (all timestamps identical), so the arbitrary path
        // dominates — which is precisely why we cap it.
        int upperBound = cap + TntPrimeMemory.HARD_EVICT_STRIDE
                + TntPrimeMemory.HARD_EVICT_ARBITRARY_CAP;
        assertThat(mem.size())
                .as("size stays bounded even under insert storm")
                .isLessThanOrEqualTo(upperBound);
        // Amortization: over `inserts - cap` over-cap insert opportunities we
        // should have fired far fewer than `overCap` sweeps — bounded by
        // ceil(overCap / STRIDE). And at least once, since we crossed the cap.
        long overCap = inserts - cap;
        long maxInvocations = (overCap / TntPrimeMemory.HARD_EVICT_STRIDE) + 1;
        assertThat(mem.hardEvictInvocations())
                .as("amortized: strictly fewer than STRIDE-cadence upper bound")
                .isBetween(1L, maxInvocations);
    }

    @Test
    void half_ttl_pass_still_evicts_stale_entries() {
        // Verifies the primary hardEvict fast-pass (halfTtl removeIf) is
        // still wired: when we push a wave of stale then a wave of fresh, the
        // stale ones should drop and we should end well under cap.
        int cap = 64;
        AtomicLong now = new AtomicLong(1_000L);
        TntPrimeMemory mem = new TntPrimeMemory(TTL, cap, now::get);
        // Wave 1: cap * 2 stale entries.
        for (int i = 0; i < cap * 2; i++) {
            mem.record(WORLD, i, 0, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "S" + i, now.get()));
        }
        // Age past halfTtl.
        now.set(now.get() + TTL);
        // Wave 2: STRIDE more over-cap fresh puts, triggering a real sweep.
        for (int i = 0; i < TntPrimeMemory.HARD_EVICT_STRIDE; i++) {
            mem.record(WORLD, 100_000 + i, 0, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "F" + i, now.get()));
        }
        // The halfTtl pass inside hardEvict should have wiped Wave 1 stale
        // entries; we should now be well under cap.
        assertThat(mem.size()).isLessThanOrEqualTo(cap);
    }

    @Test
    void clear_resets_evict_counter_so_next_storm_starts_fresh() {
        int cap = 32;
        AtomicLong now = new AtomicLong(1_000L);
        TntPrimeMemory mem = new TntPrimeMemory(TTL, cap, now::get);
        // Push STRIDE-1 over-cap inserts, ending 1 short of firing a sweep.
        int inserts = cap + TntPrimeMemory.HARD_EVICT_STRIDE - 1;
        for (int i = 0; i < inserts; i++) {
            mem.record(WORLD, i, 0, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "P" + i, now.get()));
        }
        assertThat(mem.hardEvictInvocations()).isZero();
        mem.clear();
        // After clear, another STRIDE-1 over-cap inserts should still not fire —
        // the counter must have been reset.
        for (int i = 0; i < inserts; i++) {
            mem.record(WORLD, 200_000 + i, 0, 0, TntPrimeMemory.PrimeRecord.player(
                    UUID.randomUUID(), "P" + i, now.get()));
        }
        assertThat(mem.hardEvictInvocations()).isZero();
    }
}
