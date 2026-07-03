package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W3 — regression test for the cleanupContainerSnapshots amortization
 * pattern applied in all 4 loader Events cells (Forge / NeoForge) and 4
 * Fabric bridges.
 *
 * <p>The pattern (mirrored here as a pure-Java model):</p>
 * <ol>
 *   <li>{@code cleanup()} runs the O(n) TTL scan only every {@code EVERY} calls
 *       (using {@code AtomicInteger.incrementAndGet()} + mask).</li>
 *   <li>Immediate fast-path eviction fires when snapshot map exceeds the cap
 *       (bounded work per call, still runs every open).</li>
 * </ol>
 *
 * <p>This test uses a small in-memory model to prove:</p>
 * <ul>
 *   <li>100 opens run cleanup at most {@code ceil(100/32)} times ≈ 4, not 100.</li>
 *   <li>The over-cap eviction path still runs on every call regardless.</li>
 * </ul>
 */
class ContainerCleanupAmortizationTest {

    /** Pure-Java model of the loader-side amortization. */
    private static final class Model {
        static final int CLEANUP_EVERY = 32;
        static final int MAX_SNAPSHOTS = 4;

        final AtomicInteger cleanupTick = new AtomicInteger();
        final AtomicInteger ttlScanCount = new AtomicInteger();
        final AtomicInteger evictCount = new AtomicInteger();
        int snapshotSize;

        void onContainerOpen() {
            // Fast-path: bounded eviction regardless of counter phase.
            while (snapshotSize > MAX_SNAPSHOTS) {
                snapshotSize--;
                evictCount.incrementAndGet();
            }
            // Amortized: TTL scan every N opens.
            int tick = cleanupTick.incrementAndGet();
            if ((tick & (CLEANUP_EVERY - 1)) != 0) {
                return;
            }
            ttlScanCount.incrementAndGet();
        }
    }

    @Test
    void cleanup_runsAtMostEvery32Opens() {
        Model m = new Model();
        for (int i = 0; i < 100; i++) {
            m.onContainerOpen();
        }
        // ceil(100 / 32) = at most 4 (every 32nd tick: 32, 64, 96).
        assertThat(m.ttlScanCount.get()).isLessThanOrEqualTo(4);
        assertThat(m.ttlScanCount.get()).isGreaterThan(0);
    }

    @Test
    void hundred_opens_donotRunHundredCleanups() {
        Model m = new Model();
        for (int i = 0; i < 100; i++) {
            m.onContainerOpen();
        }
        assertThat(m.ttlScanCount.get()).isLessThan(100);
        assertThat(m.ttlScanCount.get()).isLessThanOrEqualTo(5);
    }

    @Test
    void overCap_alwaysEvicts_regardlessOfCounter() {
        Model m = new Model();
        m.snapshotSize = 100;
        m.onContainerOpen();  // first call — counter is 1, so TTL scan skipped
        // But over-cap eviction still runs to bring it down.
        assertThat(m.snapshotSize).isEqualTo(Model.MAX_SNAPSHOTS);
        assertThat(m.evictCount.get()).isEqualTo(100 - Model.MAX_SNAPSHOTS);
        // The TTL scan didn't fire on this call:
        assertThat(m.ttlScanCount.get()).isZero();
    }

    @Test
    void cleanupPhase_exactly32ndOpen_triggersScan() {
        Model m = new Model();
        for (int i = 0; i < 31; i++) {
            m.onContainerOpen();
        }
        assertThat(m.ttlScanCount.get()).isZero();
        m.onContainerOpen();  // 32nd call → mask hits
        assertThat(m.ttlScanCount.get()).isEqualTo(1);
    }

    @Test
    void singleOpen_noOverCap_noWork() {
        Model m = new Model();
        m.onContainerOpen();
        // First open — counter is 1, no TTL scan; no over-cap so no evictions.
        assertThat(m.ttlScanCount.get()).isZero();
        assertThat(m.evictCount.get()).isZero();
    }

    @Test
    void thousandOpens_boundedCleanupCount() {
        Model m = new Model();
        for (int i = 0; i < 1_000; i++) {
            m.onContainerOpen();
        }
        // 1000 / 32 = 31.25 → at most 32 TTL scans across 1000 opens.
        assertThat(m.ttlScanCount.get()).isLessThanOrEqualTo(32);
        assertThat(m.ttlScanCount.get()).isGreaterThan(0);
    }
}
