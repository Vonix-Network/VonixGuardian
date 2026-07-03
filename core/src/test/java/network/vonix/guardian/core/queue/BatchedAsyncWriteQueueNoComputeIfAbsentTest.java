package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W2 — regression test that {@link BatchedAsyncWriteQueue} pre-populates
 * its per-type counter maps at boot so the hot {@code submit()} path is a plain
 * {@code get()}, not a {@code computeIfAbsent(...)} that allocates a lambda +
 * boxes a fresh {@link LongAdder} on every first-touch.
 *
 * <p>Guardrails:</p>
 * <ol>
 *   <li>Immediately after construction (no submits yet), both maps carry
 *       one entry per {@link ActionType} plus the {@code UNKNOWN} sentinel.</li>
 *   <li>Every LongAdder starts at zero.</li>
 *   <li>After a burst of submits for two distinct types, no <em>new</em> keys
 *       were added — the buckets that existed at boot are the same instances
 *       that got incremented.</li>
 * </ol>
 */
class BatchedAsyncWriteQueueNoComputeIfAbsentTest {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "vg-noca-test");
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
    void mapsArePrePopulatedForEveryActionTypeAtBoot() {
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                1024, 5_000L, 64, NOOP, DAEMON)) {
            ConcurrentHashMap<String, LongAdder> submitted = q.submittedByTypeInternal();
            ConcurrentHashMap<String, LongAdder> dropped   = q.droppedByTypeInternal();
            ConcurrentHashMap<String, BatchedAsyncWriteQueue.RateBuckets> rate =
                q.submitRateByTypeInternal();

            int expected = ActionType.values().length + 1; // + UNKNOWN sentinel
            assertThat(submitted).hasSize(expected);
            assertThat(dropped).hasSize(expected);
            assertThat(rate).hasSize(expected);

            for (ActionType t : ActionType.values()) {
                assertThat(submitted.get(t.name()))
                    .as("submitted[%s] pre-populated", t.name())
                    .isNotNull();
                assertThat(submitted.get(t.name()).sum()).isZero();
                assertThat(dropped.get(t.name()))
                    .as("dropped[%s] pre-populated", t.name())
                    .isNotNull();
                assertThat(dropped.get(t.name()).sum()).isZero();
                assertThat(rate.get(t.name()))
                    .as("submitRate[%s] pre-populated", t.name())
                    .isNotNull();
            }
            assertThat(submitted.get(BatchedAsyncWriteQueue.UNKNOWN_TYPE_KEY)).isNotNull();
            assertThat(dropped.get(BatchedAsyncWriteQueue.UNKNOWN_TYPE_KEY)).isNotNull();
            assertThat(rate.get(BatchedAsyncWriteQueue.UNKNOWN_TYPE_KEY)).isNotNull();
        }
    }

    @Test
    void submitDoesNotIntroduceNewKeys() {
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                4096, 5_000L, 128, NOOP, DAEMON)) {
            ConcurrentHashMap<String, LongAdder> submitted = q.submittedByTypeInternal();

            // Capture the exact key-set + counter-instance-identity BEFORE any submit.
            java.util.Set<String> keysBefore = new java.util.HashSet<>(submitted.keySet());
            LongAdder burnCounterBefore  = submitted.get(ActionType.BURN.name());
            LongAdder spawnCounterBefore = submitted.get(ActionType.ENTITY_SPAWN.name());

            for (int i = 0; i < 500; i++) {
                q.submit(action(ActionType.BURN));
                q.submit(action(ActionType.ENTITY_SPAWN));
            }

            // Assert: no new keys introduced. The pre-populated counters got
            // incremented — the very same instance objects.
            assertThat(submitted.keySet()).isEqualTo(keysBefore);
            assertThat(submitted.get(ActionType.BURN.name()))
                .isSameAs(burnCounterBefore);
            assertThat(submitted.get(ActionType.ENTITY_SPAWN.name()))
                .isSameAs(spawnCounterBefore);
            assertThat(burnCounterBefore.sum()).isEqualTo(500L);
            assertThat(spawnCounterBefore.sum()).isEqualTo(500L);
        }
    }
}
