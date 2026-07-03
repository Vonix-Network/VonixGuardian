/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DamageHistoryTest {

    private static final UUID VICTIM_A = UUID.fromString("00000000-0000-0000-0000-00000000000A");
    private static final UUID VICTIM_B = UUID.fromString("00000000-0000-0000-0000-00000000000B");
    private static final UUID PLAYER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void recordAndLookupInsideWindow() {
        DamageHistory dh = new DamageHistory();
        dh.record(VICTIM_A, PLAYER_1, 1_000L);
        assertThat(dh.lastPlayerToHit(VICTIM_A, 1_500L)).isEqualTo(PLAYER_1);
    }

    @Test
    void outsideWindowReturnsNullAndPrunesEntry() {
        DamageHistory dh = new DamageHistory(5_000L, 100);
        dh.record(VICTIM_A, PLAYER_1, 1_000L);
        assertThat(dh.lastPlayerToHit(VICTIM_A, 10_000L)).isNull();
        // Subsequent call must still be null; entry was pruned.
        assertThat(dh.lastPlayerToHit(VICTIM_A, 11_000L)).isNull();
    }

    @Test
    void mostRecentAttackerWins() {
        DamageHistory dh = new DamageHistory();
        dh.record(VICTIM_A, PLAYER_1, 1_000L);
        dh.record(VICTIM_A, PLAYER_2, 1_100L);
        assertThat(dh.lastPlayerToHit(VICTIM_A, 1_200L)).isEqualTo(PLAYER_2);
    }

    @Test
    void differentVictimsAreIsolated() {
        DamageHistory dh = new DamageHistory();
        dh.record(VICTIM_A, PLAYER_1, 1_000L);
        dh.record(VICTIM_B, PLAYER_2, 1_000L);
        assertThat(dh.lastPlayerToHit(VICTIM_A, 1_500L)).isEqualTo(PLAYER_1);
        assertThat(dh.lastPlayerToHit(VICTIM_B, 1_500L)).isEqualTo(PLAYER_2);
    }

    @Test
    void forgetDropsEntry() {
        DamageHistory dh = new DamageHistory();
        dh.record(VICTIM_A, PLAYER_1, 1_000L);
        dh.forget(VICTIM_A);
        assertThat(dh.lastPlayerToHit(VICTIM_A, 1_500L)).isNull();
    }

    @Test
    void clearDropsAll() {
        DamageHistory dh = new DamageHistory();
        dh.record(VICTIM_A, PLAYER_1, 1_000L);
        dh.record(VICTIM_B, PLAYER_2, 1_000L);
        dh.clear();
        assertThat(dh.size()).isZero();
    }

    @Test
    void overflowEvictsOldest() {
        DamageHistory dh = new DamageHistory(60_000L, 16);
        // v1.3.1 X6 (P1-2): eviction is now amortized — the O(n) oldest-entry
        // sweep only runs every EVICT_STRIDE=64 over-cap insert. To assert that
        // eviction still fires, push enough over-cap inserts to cross the stride
        // at least once (16 cap + 64 over-cap → guaranteed one sweep).
        for (int i = 0; i < 16 + DamageHistory.EVICT_STRIDE; i++) {
            UUID v = UUID.nameUUIDFromBytes(("victim-" + i).getBytes());
            dh.record(v, PLAYER_1, 1_000L + i);
        }
        // Amortized upper bound: cap + at most one full stride of transient overshoot.
        assertThat(dh.size()).isLessThanOrEqualTo(16 + DamageHistory.EVICT_STRIDE);
        assertThat(dh.evictions()).isGreaterThan(0L);
    }

    @Test
    void nullArgsTolerated() {
        DamageHistory dh = new DamageHistory();
        dh.record(null, PLAYER_1, 1_000L);   // no-op
        dh.record(VICTIM_A, null, 1_000L);   // no-op
        assertThat(dh.size()).isZero();
        assertThat(dh.lastPlayerToHit(null, 1_500L)).isNull();
        dh.forget(null);                      // no-op
    }

    @Test
    void invalidWindowRejected() {
        assertThatThrownBy(() -> new DamageHistory(0, 100))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DamageHistory(-1, 100))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidMaxEntriesRejected() {
        assertThatThrownBy(() -> new DamageHistory(1_000L, 15))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
