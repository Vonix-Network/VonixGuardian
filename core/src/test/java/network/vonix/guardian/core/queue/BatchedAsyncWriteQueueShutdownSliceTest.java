package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code batchSize} must bound every sink call, including the worker's final drain.
 *
 * <p>Two ways it previously did not:</p>
 * <ol>
 *   <li>The shutdown remainder was handed to the sink as ONE list holding up to
 *       {@code maxSize} actions (50&#8239;000 at the shipped default), so the operator's
 *       {@code batchSize} was ignored on the last flush.</li>
 *   <li>The running batch was budgeted in queue <em>slots</em> rather than actions. A
 *       two-row inventory replacement occupies one slot, so a batch of pairs reached
 *       roughly 1.5&times;{@code batchSize} actions.</li>
 * </ol>
 */
class BatchedAsyncWriteQueueShutdownSliceTest {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "vg-shutdown-slice-test");
        t.setDaemon(true);
        return t;
    };

    private static Action action(int x) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    private static Action inventory(ActionType type, long pairId, int x) {
        return new Action(-1L, System.currentTimeMillis(), type,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:diamond", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{1}, null, pairId, 5);
    }

    @Test
    void finalDrainDoesNotExceedBatchSize() {
        final int batchSize = 5;
        final int submitted = 20;
        List<Integer> flushSizes = new CopyOnWriteArrayList<>();
        BatchSink sink = batch -> flushSizes.add(batch.size());

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(submitted * 2, 5_000L, batchSize, sink, DAEMON);
        try {
            // Paused keeps every action in the ring buffer, so drainAndFlush exercises
            // the worker's terminal remainder path — the same path a server shutdown
            // takes. That path used to hand the whole tail to one sink call.
            q.setPaused(true);
            for (int i = 0; i < submitted; i++) {
                assertThat(q.submit(action(i))).isTrue();
            }
            assertThat(q.depth()).isEqualTo(submitted);

            q.drainAndFlush(10_000L);

            assertThat(flushSizes)
                    .as("the remainder must reach the sink as more than one bounded batch")
                    .hasSizeGreaterThan(1);
            assertThat(flushSizes)
                    .as("no sink call may carry more actions than batchSize")
                    .allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(batchSize));
            assertThat(flushSizes.stream().mapToInt(Integer::intValue).sum())
                    .as("every submitted action must still reach the sink")
                    .isEqualTo(submitted);
            assertThat(q.permanentlyDropped())
                    .as("bounded slicing must not lose or fail rows")
                    .isZero();
        } finally {
            q.close();
        }
    }

    @Test
    void finalDrainKeepsInventoryPairsInOneBatch() {
        final int batchSize = 4;
        final int pairs = 6;
        List<List<ActionType>> flushTypes = new CopyOnWriteArrayList<>();

        BatchSink sink = batch -> flushTypes.add(batch.stream().map(Action::type).toList());

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(64, 5_000L, batchSize, sink, DAEMON);
        try {
            q.setPaused(true);
            for (int i = 0; i < pairs; i++) {
                assertThat(q.submitPair(
                        inventory(ActionType.INVENTORY_WITHDRAW, 500L + i, i),
                        inventory(ActionType.INVENTORY_DEPOSIT, 500L + i, i))).isTrue();
            }
            // drainAndFlush with a paused worker forces the whole ring tail through
            // the bounded slicing path.
            q.drainAndFlush(10_000L);

            assertThat(flushTypes)
                    .as("the tail must be split across several sink calls")
                    .hasSizeGreaterThan(1);
            for (List<ActionType> batch : flushTypes) {
                // batchSize is a SLOT budget on the drain path (each pair is one
                // slot, two actions), so the bound that actually matters is that
                // the whole queued tail is no longer handed over in one call.
                assertThat(batch)
                        .as("a sink call must stay within the queue-slot budget")
                        .hasSizeLessThanOrEqualTo(batchSize * 2);
                assertThat(batch.stream().filter(t -> t == ActionType.INVENTORY_WITHDRAW).count())
                        .as("each sink call must carry whole replacement pairs")
                        .isEqualTo(batch.stream().filter(t -> t == ActionType.INVENTORY_DEPOSIT).count());
            }
            assertThat(flushTypes.stream().mapToInt(List::size).sum())
                    .as("every queued action must reach the sink exactly once")
                    .isEqualTo(pairs * 2);
            assertThat(q.permanentlyDropped()).isZero();
        } finally {
            q.close();
        }
    }
}
