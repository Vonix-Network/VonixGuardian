package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W4 — regression test for {@link BatchedAsyncWriteQueue#submitRateByType()}
 * and {@link BatchedAsyncWriteQueue#allocationRatePerSecond()}.
 *
 * <p>Approach: submit a known burst of {@code N} events for two distinct
 * {@link ActionType}s, immediately snapshot the rate, and verify the observed
 * events/second is within a coarse bound of {@code N / RATE_WINDOW_SECONDS}
 * (rate meter averages over the last 30s, so a burst just after boot yields
 * exactly that quotient).</p>
 *
 * <p>The meter uses {@link System#nanoTime()} directly for simplicity; tests
 * don't try to freeze time and instead assert against a tolerant band: the
 * expected minimum is {@code N/30} events/sec (all events landed in the same
 * bucket) and the expected maximum is also {@code N/30} (with at most one extra
 * second of skew from the 1-sec bucket granularity if the burst straddles a
 * bucket boundary, which we allow for).</p>
 */
class BatchedAsyncWriteQueueSubmitRateTest {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "vg-rate-test");
        t.setDaemon(true);
        return t;
    };

    private static final BatchSink NOOP = batch -> { /* discard */ };

    private static Action action(ActionType type) {
        return new Action(-1L, System.currentTimeMillis(), type,
            UUID.randomUUID(), "tester", "minecraft:overworld",
            0, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    @Test
    void allocationRateIsZeroBeforeAnySubmit() {
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(1024, 5_000L, 64, NOOP, DAEMON)) {
            assertThat(q.allocationRatePerSecond()).isEqualTo(0.0);
            assertThat(q.submitRateByType()).isEmpty();
        }
    }

    @Test
    void submitRateReflectsKnownBurstPerType() {
        final int burstBurn = 60;
        final int burstSpread = 90;
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(4096, 5_000L, 128, NOOP, DAEMON)) {
            for (int i = 0; i < burstBurn; i++) {
                q.submit(action(ActionType.BURN));
            }
            for (int i = 0; i < burstSpread; i++) {
                q.submit(action(ActionType.SPREAD));
            }

            Map<String, Double> rate = q.submitRateByType();

            // Both types should appear.
            assertThat(rate).containsKeys("BURN", "SPREAD");

            double window = (double) BatchedAsyncWriteQueue.RATE_WINDOW_SECONDS;
            double expectedBurnMin  = burstBurn   / window;
            double expectedSpreadMin= burstSpread / window;
            // Burst may straddle one bucket boundary → could be up to 2 buckets. That still
            // sums to the same total (all our submits are inside the window), so lower
            // bound is exact: N/window. Upper bound also N/window (no other submits).
            assertThat(rate.get("BURN"))
                .as("BURN rate over 30s window: expected %.4f/s", expectedBurnMin)
                .isCloseTo(expectedBurnMin, org.assertj.core.data.Offset.offset(0.001));
            assertThat(rate.get("SPREAD"))
                .as("SPREAD rate over 30s window: expected %.4f/s", expectedSpreadMin)
                .isCloseTo(expectedSpreadMin, org.assertj.core.data.Offset.offset(0.001));

            // Aggregate allocation rate is the sum.
            double expectedTotal = (burstBurn + burstSpread) / window;
            assertThat(q.allocationRatePerSecond())
                .isCloseTo(expectedTotal, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void unusedTypesDoNotAppearInSnapshot() {
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(1024, 5_000L, 64, NOOP, DAEMON)) {
            for (int i = 0; i < 30; i++) {
                q.submit(action(ActionType.IGNITE));
            }
            Map<String, Double> rate = q.submitRateByType();
            assertThat(rate).containsOnlyKeys("IGNITE");
            assertThat(rate.get("IGNITE")).isCloseTo(30.0 / BatchedAsyncWriteQueue.RATE_WINDOW_SECONDS,
                org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void rateBucketsAreConsistentAcrossManyTypes() {
        // Verify that 8 mixin-sourced ActionTypes all get correctly bucketed
        // without collision in the ring.
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(4096, 5_000L, 128, NOOP, DAEMON)) {
            ActionType[] types = {
                ActionType.BURN, ActionType.SPREAD, ActionType.IGNITE, ActionType.FADE,
                ActionType.FORM, ActionType.LEAVES_DECAY, ActionType.DISPENSE,
                ActionType.ENTITY_CHANGE_BLOCK
            };
            for (ActionType t : types) {
                for (int i = 0; i < 30; i++) q.submit(action(t));
            }
            Map<String, Double> rate = q.submitRateByType();
            double expected = 30.0 / BatchedAsyncWriteQueue.RATE_WINDOW_SECONDS;
            for (ActionType t : types) {
                assertThat(rate).containsKey(t.name());
                assertThat(rate.get(t.name())).isCloseTo(expected,
                    org.assertj.core.data.Offset.offset(0.001));
            }
            // aggregate = 8 * expected
            assertThat(q.allocationRatePerSecond()).isCloseTo(8.0 * expected,
                org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    void snapshotIsUnmodifiable() {
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(64, 5_000L, 8, NOOP, DAEMON)) {
            q.submit(action(ActionType.BURN));
            Map<String, Double> snap = q.submitRateByType();
            assertThat(snap).isNotNull();
            try {
                snap.put("BOGUS", 1.0);
                org.assertj.core.api.Assertions.fail("submitRateByType() must return an unmodifiable map");
            } catch (UnsupportedOperationException expected) { /* ok */ }
        }
    }
}
