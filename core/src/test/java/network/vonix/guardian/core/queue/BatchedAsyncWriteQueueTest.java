package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BatchedAsyncWriteQueueTest {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "vg-queue-test");
        t.setDaemon(true);
        return t;
    };

    private static Action action(int x) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    /** Latch + capture sink for assertion-friendly tests (no Mockito). */
    private static final class CapturingSink implements BatchSink {
        final CountDownLatch latch;
        final List<Action> seen = new CopyOnWriteArrayList<>();
        final AtomicInteger batches = new AtomicInteger();

        CapturingSink(int expectedItems) {
            this.latch = new CountDownLatch(expectedItems);
        }

        @Override
        public void flush(List<Action> batch) {
            batches.incrementAndGet();
            seen.addAll(batch);
            for (int i = 0; i < batch.size(); i++) {
                latch.countDown();
            }
        }
    }

    private static final class RejectingPoisonSink implements BatchSink {
        final List<Action> seen = new CopyOnWriteArrayList<>();
        private final java.util.Set<Integer> poisonXs;

        RejectingPoisonSink(int... poisonXs) {
            this.poisonXs = java.util.Arrays.stream(poisonXs)
                    .boxed()
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public void flush(List<Action> batch) {
            if (batch.stream().anyMatch(a -> poisonXs.contains(a.x()))) {
                throw new IllegalArgumentException("poison action in batch");
            }
            seen.addAll(batch);
        }
    }

    private static final class AlwaysFailingSink implements BatchSink {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void flush(List<Action> batch) {
            attempts.incrementAndGet();
            throw new RuntimeException("database down");
        }
    }

    private static final class BlockingSink implements BatchSink {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void flush(List<Action> batch) throws Exception {
            entered.countDown();
            while (!release.await(100, TimeUnit.MILLISECONDS)) {
                // Deliberately ignore queue-worker interrupts so drainAndFlush timeout
                // exercises the worker-still-alive path.
            }
        }
    }

    @Test
    void submitAndFlush_deliversAllItems() throws Exception {
        CapturingSink sink = new CapturingSink(5);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(64, 50L, 3, sink, DAEMON)) {
            for (int i = 0; i < 5; i++) {
                q.submit(action(i));
            }
            assertThat(sink.latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.seen).hasSize(5);
            // 5 items at batchSize=3 → at least 2 sink invocations.
            assertThat(sink.batches.get()).isGreaterThanOrEqualTo(2);
            assertThat(q.dropped()).isZero();
        }
    }

    @Test
    void submit_whenFull_incrementsDroppedCounter() throws Exception {
        // A blocking sink keeps the worker (and its currently-held item) parked, so the
        // queue's ring buffer fills up at exactly maxSize.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        BatchSink blockingSink = batch -> {
            entered.countDown();
            release.await();
        };

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(4, 25L, 1, blockingSink, DAEMON);
        try {
            // First submit gets picked up and parks the worker inside flush().
            q.submit(action(0));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Now fill the ring buffer (capacity 4) and overflow it.
            for (int i = 0; i < 4; i++) {
                q.submit(action(i));
            }
            // These should all be dropped.
            for (int i = 0; i < 10; i++) {
                q.submit(action(100 + i));
            }
            assertThat(q.dropped()).isGreaterThanOrEqualTo(10L);
            assertThat(q.depth()).isLessThanOrEqualTo(4);
        } finally {
            release.countDown();
            q.drainAndFlush(2_000L);
        }
    }

    @Test
    void drainAndFlush_drainsRemainingItems() throws Exception {
        // Long flush interval so the worker is parked in poll() most of the time and
        // drainAndFlush has to actually wake it / pick up the leftovers.
        CapturingSink sink = new CapturingSink(20);
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(64, 5_000L, 8, sink, DAEMON);
        for (int i = 0; i < 20; i++) {
            q.submit(action(i));
        }
        q.drainAndFlush(2_000L);
        assertThat(sink.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.seen).hasSize(20);
        assertThat(q.depth()).isZero();
    }

    @Test
    void sinkThrows_retriesThenPermanentlyDrops() throws Exception {
        BatchSink mockSink = mock(BatchSink.class);
        CountDownLatch attempts = new CountDownLatch(BatchedAsyncWriteQueue.MAX_SINK_RETRIES);
        doAnswer((InvocationOnMock inv) -> {
            attempts.countDown();
            throw new RuntimeException("nope");
        }).when(mockSink).flush(anyList());

        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 25L, 4, mockSink, DAEMON)) {
            q.submit(action(1));
            assertThat(attempts.await(3, TimeUnit.SECONDS))
                    .as("sink should be retried MAX_SINK_RETRIES times")
                    .isTrue();
            // Give the worker a tick to record the permanent drop.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (q.permanentlyDropped() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(q.permanentlyDropped()).isEqualTo(1L);
            verify(mockSink, atLeast(BatchedAsyncWriteQueue.MAX_SINK_RETRIES)).flush(anyList());
        }
    }

    @Test
    void poisonActionDoesNotDropGoodActionsFromSameBatch() {
        RejectingPoisonSink sink = new RejectingPoisonSink(99);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 5_000L, 8, sink, DAEMON)) {
            q.setPaused(true);
            q.submit(action(1));
            q.submit(action(2));
            q.submit(action(99));
            q.submit(action(3));
            q.submit(action(4));

            q.drainAndFlush(10_000L);

            assertThat(sink.seen).extracting(Action::x).containsExactly(1, 2, 3, 4);
            assertThat(q.permanentlyDropped()).isEqualTo(1L);
            assertThat(q.dropped()).isZero();
        }
    }

    @Test
    void allPoisonActionsAreCountedAsPermanentDrops() {
        RejectingPoisonSink sink = new RejectingPoisonSink(10, 11);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 5_000L, 8, sink, DAEMON)) {
            q.setPaused(true);
            q.submit(action(10));
            q.submit(action(11));

            q.drainAndFlush(10_000L);

            assertThat(sink.seen).isEmpty();
            assertThat(q.permanentlyDropped()).isEqualTo(2L);
            assertThat(q.dropped()).isZero();
        }
    }

    @Test
    void globalSinkFailureDoesNotBisectWholeBatch() {
        AlwaysFailingSink sink = new AlwaysFailingSink();
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 5_000L, 8, sink, DAEMON)) {
            q.setPaused(true);
            for (int i = 0; i < 8; i++) {
                q.submit(action(i));
            }

            long start = System.nanoTime();
            q.drainAndFlush(5_000L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(sink.attempts.get()).isEqualTo(BatchedAsyncWriteQueue.MAX_SINK_RETRIES);
            assertThat(q.permanentlyDropped()).isEqualTo(8L);
            assertThat(q.dropped()).isZero();
            assertThat(elapsedMs).isLessThan(2_000L);
        }
    }

    @Test
    void drainAndFlushTimeoutDoesNotStartConcurrentCallerSideFlush() throws Exception {
        BlockingSink sink = new BlockingSink();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 5_000L, 1, sink, DAEMON);
        try {
            q.submit(action(1));
            assertThat(sink.entered.await(2, TimeUnit.SECONDS)).isTrue();
            q.submit(action(2));
            q.submit(action(3));

            long start = System.nanoTime();
            q.drainAndFlush(25L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(elapsedMs).isLessThan(1_000L);
            assertThat(q.permanentlyDropped()).isEqualTo(2L);
        } finally {
            sink.release.countDown();
        }
    }

    @Test
    void steadyTrickle_stillFlushesWithinWindow() throws Exception {
        // REGRESSION: previously, the worker only flushed when poll() timed out (head ==
        // null) or batchSize was reached. A steady arrival rate that kept poll() returning
        // a non-null head while never filling batchSize meant the batch sat forever, hence
        // /vg lookup couldn't see events until shutdown forced drainAndFlush. This test
        // submits items at a cadence faster than flushIntervalMs (50 ms here: one every
        // ~10 ms) but slower than batchSize=100, and asserts they reach the sink within
        // one flush-window-and-change. Don't change the flushIntervalMs / cadence ratio
        // without thinking about what's being asserted.
        CapturingSink sink = new CapturingSink(20);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(256, 50L, 100, sink, DAEMON)) {
            for (int i = 0; i < 20; i++) {
                q.submit(action(i));
                Thread.sleep(10);  // steady trickle, never lets the poll() time out
            }
            // 20 items at one-every-10ms = ~200ms wall, plus one flush window (50ms).
            // 1 second is generous.
            assertThat(sink.latch.await(1, TimeUnit.SECONDS))
                    .as("steady trickle must flush within window even without batchSize")
                    .isTrue();
            assertThat(sink.seen).hasSize(20);
            assertThat(q.dropped()).isZero();
        }
    }

    @Test
    void pendingSnapshotReturnsQueuedItemsWithoutDraining() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        BatchSink blockingSink = batch -> {
            entered.countDown();
            release.await();
        };

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 5_000L, 1, blockingSink, DAEMON);
        try {
            q.submit(action(0));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            q.submit(action(10));
            q.submit(action(11));

            List<Action> pending = q.pendingSnapshot();

            assertThat(pending).extracting(Action::x).containsExactly(10, 11);
            assertThat(q.depth()).isEqualTo(2);
        } finally {
            release.countDown();
            q.drainAndFlush(2_000L);
        }
    }

    @Test
    void close_terminatesWorkerThread() throws Exception {
        CapturingSink sink = new CapturingSink(0);
        List<Thread> spawned = new ArrayList<>();
        ThreadFactory tracking = r -> {
            Thread t = new Thread(r, "vg-queue-leak-test");
            t.setDaemon(true);
            spawned.add(t);
            return t;
        };

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 25L, 4, sink, tracking);
        q.submit(action(1));
        q.close();

        assertThat(spawned).hasSize(1);
        spawned.get(0).join(2_000L);
        assertThat(spawned.get(0).isAlive())
                .as("worker thread must exit after close()")
                .isFalse();
    }
}
