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
 * <p>The terminal remainder path in {@code flushWorkerRemainder} handed the whole ring
 * tail to a single {@link BatchSink#flush}. That tail can hold up to {@code maxSize}
 * actions (50&#8239;000 at the shipped default), so {@code batchSize} was ignored on the
 * last flush of a shutdown: one oversized JDBC transaction, and with
 * {@link network.vonix.guardian.core.storage.IdempotentAuditSink} the entire tail encoded
 * into a single {@code vg_sink_outbox} payload. One poison row also condemned the whole
 * tail instead of one bounded slice.</p>
 *
 * <p><b>Why this test reaches that path deterministically.</b> The worker is paused and
 * waited on until the queue reports itself idle, which can only happen while the worker is
 * parked in its paused sleep — a paused worker never polls. Actions are submitted after
 * that, then {@code drainAndFlush} publishes shutdown. On waking, the paused branch's
 * {@code if (shutdown) break} is the first statement, so the loop exits with a full ring
 * buffer and the <em>terminal</em> path is the only path that can flush it. Without that
 * wait the worker could instead take the normal poll path, where the old code already
 * honoured {@code batchSize} and this test would prove nothing.</p>
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
    void terminalDrainDoesNotExceedBatchSize() throws Exception {
        final int batchSize = 5;
        final int submitted = 20;
        List<Integer> flushSizes = new CopyOnWriteArrayList<>();

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                submitted * 2, 5_000L, batchSize, batch -> flushSizes.add(batch.size()), DAEMON);
        try {
            q.setPaused(true);
            assertThat(q.awaitIdle(2_000L))
                    .as("worker must be parked in the paused loop before we rely on the terminal path")
                    .isTrue();

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
    void terminalDrainKeepsInventoryPairsInOneBatch() throws Exception {
        final int batchSize = 4;
        final int pairs = 6;
        List<List<ActionType>> flushTypes = new CopyOnWriteArrayList<>();

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                64, 5_000L, batchSize, batch -> flushTypes.add(batch.stream().map(Action::type).toList()), DAEMON);
        try {
            q.setPaused(true);
            assertThat(q.awaitIdle(2_000L)).isTrue();

            for (int i = 0; i < pairs; i++) {
                assertThat(q.submitPair(
                        inventory(ActionType.INVENTORY_WITHDRAW, 500L + i, i),
                        inventory(ActionType.INVENTORY_DEPOSIT, 500L + i, i))).isTrue();
            }

            q.drainAndFlush(10_000L);

            assertThat(flushTypes)
                    .as("the tail must be split across several sink calls")
                    .hasSizeGreaterThan(1);
            for (List<ActionType> batch : flushTypes) {
                assertThat(batch)
                        .as("a terminal slice must stay within batchSize actions")
                        .hasSizeLessThanOrEqualTo(batchSize);
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
