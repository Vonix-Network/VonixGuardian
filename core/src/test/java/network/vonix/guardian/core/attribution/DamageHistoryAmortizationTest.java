package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X6 (P1-2): amortized eviction. The O(n) oldest-entry sweep must fire
 * at least once when we push more than {@code EVICT_STRIDE} over-cap inserts,
 * and the map must NOT grow unbounded.
 */
class DamageHistoryAmortizationTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void steady_state_stays_bounded_by_cap_plus_stride() {
        int cap = 128;
        DamageHistory dh = new DamageHistory(60_000L, cap);
        // Push 10x cap distinct victims — sweep must run enough times to keep
        // us bounded near the cap. Amortized bound: cap + STRIDE.
        int inserts = cap * 10;
        for (int i = 0; i < inserts; i++) {
            UUID v = UUID.nameUUIDFromBytes(("v-" + i).getBytes());
            dh.record(v, PLAYER, 1_000L + i);
        }
        assertThat(dh.size()).isLessThanOrEqualTo(cap + DamageHistory.EVICT_STRIDE);
        assertThat(dh.evictions()).isGreaterThan(0L);
    }

    @Test
    void under_cap_never_triggers_sweep() {
        int cap = 64;
        DamageHistory dh = new DamageHistory(60_000L, cap);
        // At-or-under cap: sweep must NEVER fire.
        for (int i = 0; i < cap; i++) {
            UUID v = UUID.nameUUIDFromBytes(("under-" + i).getBytes());
            dh.record(v, PLAYER, 1_000L + i);
        }
        assertThat(dh.evictions()).isZero();
    }

    @Test
    void sweep_stride_matches_documented_constant() {
        // Guard rail: if someone later inlines the stride to a different number,
        // this test will catch the drift.
        assertThat(DamageHistory.EVICT_STRIDE).isEqualTo(64);
    }
}
