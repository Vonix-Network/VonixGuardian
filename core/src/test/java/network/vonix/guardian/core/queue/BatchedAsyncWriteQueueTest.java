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
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * Global sink failure that also plants a sticky worker interrupt on the
     * first attempt, matching drainAndFlush's wake-interrupt race against
     * retry backoff.
     */
    private static final class ShutdownWakeFailingSink implements BatchSink {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void flush(List<Action> batch) {
            int n = attempts.incrementAndGet();
            if (n == 1) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("database down");
        }
    }

    private static final class InterruptingFailingSink implements BatchSink {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void flush(List<Action> batch) {
            attempts.incrementAndGet();
            // The retry backoff observes this interrupt and must not leave the
            // flag set while QuarantineStore opens and forces its journal.
            Thread.currentThread().interrupt();
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
    void exhaustedSinkRetryIsDurableAndRecoversAfterRestart() throws Exception {
        Path dir = Files.createTempDirectory("vg-quarantine-test");
        Path journal = dir.resolve("quarantine.bin");
        AlwaysFailingSink failing = new AlwaysFailingSink();
        BatchedAsyncWriteQueue first = new BatchedAsyncWriteQueue(16, 25L, 4, failing, DAEMON, journal);
        first.setPaused(true);
        first.submit(action(700));
        first.drainAndFlush(5_000L);

        assertThat(first.quarantined()).isEqualTo(1L);
        assertThat(Files.size(journal)).isGreaterThan(0L);

        CapturingSink recovered = new CapturingSink(1);
        BatchedAsyncWriteQueue second = new BatchedAsyncWriteQueue(16, 25L, 4, recovered, DAEMON, journal);
        try {
            assertThat(recovered.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recovered.seen).extracting(Action::x).containsExactly(700);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (second.quarantined() != 0L && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(second.quarantined()).isZero();
            assertThat(second.recoveredFromQuarantine()).isEqualTo(1L);
        } finally {
            second.close();
        }
    }

    @Test
    void interruptedRetryStillForcesQuarantineAndRepeatsInSameJvm() throws Exception {
        Path dir = Files.createTempDirectory("vg-interrupted-quarantine-test");
        Path journal = dir.resolve("quarantine.bin");

        for (int iteration = 0; iteration < 2; iteration++) {
            InterruptingFailingSink failing = new InterruptingFailingSink();
            BatchedAsyncWriteQueue first = new BatchedAsyncWriteQueue(
                    16, 25L, 4, failing, DAEMON, journal);
            try {
                first.submit(action(800 + iteration));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
                while (first.quarantined() == 0L && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertThat(first.quarantined()).isEqualTo(1L);
                assertThat(failing.attempts.get()).isEqualTo(1);
                assertThat(Files.size(journal)).isGreaterThan(0L);
            } finally {
                first.close();
            }

            CapturingSink recovered = new CapturingSink(1);
            BatchedAsyncWriteQueue second = new BatchedAsyncWriteQueue(
                    16, 25L, 4, recovered, DAEMON, journal);
            try {
                assertThat(recovered.latch.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(recovered.seen).extracting(Action::x)
                        .containsExactly(800 + iteration);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
                while (second.quarantined() != 0L && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                assertThat(second.quarantined()).isZero();
                assertThat(second.recoveredFromQuarantine()).isEqualTo(1L);
            } finally {
                second.close();
            }
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
    void globalSinkFailureKeepsFullRetryBudgetWhenShutdownWakeLandsMidFlush() {
        // REGRESSION: drainAndFlush interrupts the worker to wake poll/sleep. That
        // wake signal used to remain sticky into retry backoff, so a global DB-down
        // failure permanently dropped after attempt 1/MAX_SINK_RETRIES and skipped
        // the non-bisection path's intended retry budget.
        ShutdownWakeFailingSink sink = new ShutdownWakeFailingSink();
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
    void submitAfterCloseIsRejectedAndCountedAsDropped() {
        CapturingSink sink = new CapturingSink(0);
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 25L, 4, sink, DAEMON);

        q.close();

        assertThat(q.submit(action(42))).isFalse();
        assertThat(q.depth()).isZero();
        assertThat(q.dropped()).isEqualTo(1L);
    }

    @Test
    void inventoryReplacementPairOccupiesOneSlotAndSurvivesSaturationTogether() throws Exception {
        CapturingSink sink = new CapturingSink(0);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(1, 5_000L, 4, sink, DAEMON)) {
            q.setPaused(true);
            Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 77L, 1);
            Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 77L, 2);
            assertThat(q.submitPair(withdraw, deposit)).isTrue();
            assertThat(q.depth()).isEqualTo(1);
            assertThat(q.pendingSnapshot()).extracting(Action::type)
                    .containsExactly(ActionType.INVENTORY_WITHDRAW, ActionType.INVENTORY_DEPOSIT);
            assertThat(q.submit(action(9))).isFalse();
            assertThat(q.dropped()).isEqualTo(1L);
            assertThat(q.pendingSnapshot()).hasSize(2);
        }
    }

    @Test
    void inventoryReplacementPairIsRejectedAsAUnitWhenQueueIsFull() throws Exception {
        CapturingSink sink = new CapturingSink(0);
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(1, 5_000L, 4, sink, DAEMON)) {
            q.setPaused(true);
            assertThat(q.submit(action(1))).isTrue();
            Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 88L, 1);
            Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 88L, 2);
            assertThat(q.submitPair(withdraw, deposit)).isFalse();
            assertThat(q.dropped()).isEqualTo(2L);
            assertThat(q.pendingSnapshot()).extracting(Action::x).containsExactly(1);
        }
    }

    @Test
    void inventoryReplacementPairIsFlushedAndQuarantinedTogetherOnSinkFailure() throws Exception {
        Path dir = Files.createTempDirectory("vg-pair-quarantine-test");
        Path journal = dir.resolve("quarantine.bin");
        AlwaysFailingSink failing = new AlwaysFailingSink();
        BatchedAsyncWriteQueue first = new BatchedAsyncWriteQueue(8, 25L, 4, failing, DAEMON, journal);
        try {
            Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 91L, 1);
            Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 91L, 2);
            assertThat(first.submitPair(withdraw, deposit)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (first.quarantined() < 2L && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(first.quarantined()).isEqualTo(2L);
        } finally {
            first.close();
        }

        CapturingSink recovered = new CapturingSink(2);
        BatchedAsyncWriteQueue second = new BatchedAsyncWriteQueue(8, 25L, 4, recovered, DAEMON, journal);
        try {
            assertThat(recovered.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recovered.seen).extracting(Action::type)
                    .containsExactlyInAnyOrder(ActionType.INVENTORY_WITHDRAW, ActionType.INVENTORY_DEPOSIT);
            assertThat(recovered.seen).extracting(Action::pairId).containsOnly(91L);
        } finally {
            second.close();
        }
    }

    @Test
    void survivingPairHalfIsNeverReflushedAsSingleton() throws Exception {
        Path dir = Files.createTempDirectory("vg-lone-pair");
        Path journal = dir.resolve("quarantine.bin");
        Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 92L, 1);
        Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 92L, 2);
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = seed.appendGroup(List.of(withdraw, deposit));
        seed.acknowledge(sequences.get(0));

        AtomicInteger flushes = new AtomicInteger();
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        BatchSink sink = batch -> {
            flushes.incrementAndGet();
            sizes.add(batch.size());
        };
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 25L, 4, sink, DAEMON, journal);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (System.nanoTime() < deadline) {
                Thread.sleep(50L);
            }
            assertThat(flushes.get())
                    .as("lone pair member must never reflush as a singleton")
                    .isZero();
            assertThat(sizes).isEmpty();
            assertThat(q.quarantined()).isEqualTo(1L);
        } finally {
            q.close();
        }
    }

    @Test
    void loneSinkSucceededPairMemberIsRetainedForRepairAfterRestart() throws Exception {
        Path dir = Files.createTempDirectory("vg-lone-marked-pair");
        Path journal = dir.resolve("quarantine.bin");
        Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 94L, 1);
        Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 94L, 2);
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = seed.appendGroup(List.of(withdraw, deposit));
        seed.acknowledge(sequences.get(0));
        seed.markSinkSucceeded(sequences.get(1));

        AtomicInteger flushes = new AtomicInteger();
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                8, 25L, 4, batch -> flushes.incrementAndGet(), DAEMON, journal)) {
            Thread.sleep(1_800L);
            assertThat(flushes.get()).isZero();
            assertThat(q.recoveredFromQuarantine()).isZero();
        }

        QuarantineStore after = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(after.entries())
                .as("a lone sink-succeeded replacement member needs explicit repair, never singleton ACK")
                .singleElement()
                .satisfies(entry -> assertThat(entry.sinkSucceeded()).isTrue());
    }

    @Test
    void mixedPairSinkMarkersAreRetainedForRepairAfterRestart() throws Exception {
        Path dir = Files.createTempDirectory("vg-mixed-marked-pair");
        Path journal = dir.resolve("quarantine.bin");
        Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 95L, 1);
        Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 95L, 2);
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        List<Long> sequences = seed.appendGroup(List.of(withdraw, deposit));
        seed.markSinkSucceeded(sequences.get(0));

        AtomicInteger flushes = new AtomicInteger();
        try (BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                8, 25L, 4, batch -> flushes.incrementAndGet(), DAEMON, journal)) {
            Thread.sleep(1_800L);
            assertThat(flushes.get()).isZero();
            assertThat(q.recoveredFromQuarantine()).isZero();
        }

        QuarantineStore after = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(after.entries())
                .as("mixed marker state is not proof that both pair sink effects completed")
                .hasSize(2);
        assertThat(after.entries()).extracting(QuarantineStore.Entry::sinkSucceeded)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void groupMarkerFailureRetriesWithoutSplittingPair() throws Exception {
        Path dir = Files.createTempDirectory("vg-group-marker");
        Path journal = dir.resolve("quarantine.bin");
        Action withdraw = inventory(ActionType.INVENTORY_WITHDRAW, 93L, 1);
        Action deposit = inventory(ActionType.INVENTORY_DEPOSIT, 93L, 2);
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        seed.appendGroup(List.of(withdraw, deposit));

        ControllableGroupMarkerStore failing = new ControllableGroupMarkerStore(journal);
        AtomicInteger flushes = new AtomicInteger();
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        BatchSink sink = batch -> {
            flushes.incrementAndGet();
            sizes.add(batch.size());
        };
        BatchedAsyncWriteQueue first = new BatchedAsyncWriteQueue(8, 25L, 4, sink, DAEMON, failing);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((flushes.get() < 1 || failing.groupMarkerAttempts() < 1)
                    && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(flushes.get()).isEqualTo(1);
            assertThat(sizes).containsExactly(2);
            assertThat(first.recoveredFromQuarantine()).isZero();
            failing.allowMarkers();
            long recoverDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
            while (first.recoveredFromQuarantine() < 2L && System.nanoTime() < recoverDeadline) {
                Thread.sleep(50L);
            }
            assertThat(first.recoveredFromQuarantine()).isEqualTo(2L);
            assertThat(flushes.get())
                    .as("group marker retry must not reflush the pair")
                    .isEqualTo(1);
            assertThat(first.quarantined()).isZero();
        } finally {
            first.close();
        }
    }

    @Test
    void idleBarrierCannotObserveEmptyQueueBeforeWorkerPublishesPollState() throws Exception {
        CapturingSink sink = new CapturingSink(1);
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        Runnable pollProbe = () -> {
            pollEntered.countDown();
            try {
                assertThat(releasePoll.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                16, 25L, 1, sink, DAEMON, (QuarantineStore) null,
                null, null, null, pollProbe);
        AtomicBoolean idle = new AtomicBoolean();
        CountDownLatch idleReturned = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                idle.set(q.awaitIdle(2_000L));
            } finally {
                idleReturned.countDown();
            }
        }, "vg-poll-state-waiter");
        try {
            assertThat(q.submit(action(779))).isTrue();
            assertThat(pollEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(q.freezeAdmission()).isTrue();
            waiter.start();
            assertThat(idleReturned.await(150, TimeUnit.MILLISECONDS))
                    .as("idle observation must wait for the worker poll handoff")
                    .isFalse();
            releasePoll.countDown();
            assertThat(sink.latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(idleReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(idle).isTrue();
        } finally {
            releasePoll.countDown();
            if (waiter.isAlive()) {
                waiter.join(2_000L);
            }
            q.close();
        }
    }

    @Test
    void idleBarrierRechecksRecoveryAfterWorkerQuarantineRace() throws Exception {
        Path journal = Files.createTempFile("vg-idle-recovery-race", ".bin");
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        Runnable idleProbe = () -> {
            probeEntered.countDown();
            try {
                assertThat(releaseProbe.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };
        AlwaysFailingSink sink = new AlwaysFailingSink();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                16, 25L, 1, sink, DAEMON, new QuarantineStore(journal, 8, 1_000_000L),
                null, null, idleProbe);
        AtomicBoolean idle = new AtomicBoolean();
        Thread waiter = new Thread(() -> idle.set(q.isPipelineIdle()), "vg-idle-race-waiter");
        try {
            q.setPaused(true);
            assertThat(q.submit(action(778))).isTrue();
            assertThat(q.freezeAdmission()).isTrue();
            waiter.start();
            assertThat(probeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            q.setPaused(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4L);
            while (q.quarantined() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(q.quarantined())
                    .as("normal sink failure must add durable recovery while idle check is paused")
                    .isEqualTo(1L);
            releaseProbe.countDown();
            waiter.join(2_000L);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(idle)
                    .as("idle barrier must recheck recovery after the worker quarantines a batch")
                    .isFalse();
        } finally {
            releaseProbe.countDown();
            if (waiter.isAlive()) {
                waiter.join(2_000L);
            }
            q.close();
        }
    }

    @Test
    void freezeAdmissionDoesNotReportIdleWhileDurableRecoveryRemainsPending() throws Exception {
        Path journal = Files.createTempFile("vg-migrate-recovery-idle", ".bin");
        QuarantineStore store = new QuarantineStore(journal, 8, 1_000_000L);
        store.append(action(777));
        AlwaysFailingSink sink = new AlwaysFailingSink();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 25L, 1, sink, DAEMON, store);
        try {
            assertThat(q.freezeAdmission()).isTrue();
            Thread.sleep(100L);
            assertThat(q.isPipelineIdle())
                    .as("migrate-db must wait for pending durable recovery before copying")
                    .isFalse();
        } finally {
            q.close();
        }
    }

    @Test
    void freezeAdmissionWaitsForSinkIdleAndRejectsOffers() throws Exception {
        CountDownLatch inSink = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BatchSink sink = batch -> {
            inSink.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release timeout");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        };
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 25L, 4, sink, DAEMON);
        try {
            assertThat(q.submit(action(1))).isTrue();
            assertThat(inSink.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(q.freezeAdmission()).isTrue();
            assertThat(q.submit(action(2))).isFalse();
            AtomicBoolean idle = new AtomicBoolean(false);
            Thread waiter = new Thread(() -> idle.set(q.awaitIdle(3_000L)), "idle-waiter");
            waiter.start();
            Thread.sleep(80L);
            assertThat(idle.get()).isFalse();
            release.countDown();
            waiter.join(4_000L);
            assertThat(idle.get()).isTrue();
            assertThat(q.isPipelineIdle()).isTrue();
        } finally {
            release.countDown();
            q.close();
        }
    }

    private static final class ControllableGroupMarkerStore extends QuarantineStore {
        private final AtomicInteger groupMarkerAttempts = new AtomicInteger();
        private volatile boolean allow;

        ControllableGroupMarkerStore(Path path) throws Exception {
            super(path, 8, 1_000_000L);
        }

        void allowMarkers() {
            allow = true;
        }

        int groupMarkerAttempts() {
            return groupMarkerAttempts.get();
        }

        @Override
        synchronized void markSinkSucceededGroup(List<Long> sequences) throws java.io.IOException {
            groupMarkerAttempts.incrementAndGet();
            if (!allow) {
                throw new java.io.IOException("controlled group marker failure");
            }
            super.markSinkSucceededGroup(sequences);
        }
    }

    private static Action inventory(ActionType type, long pairId, int x) {
        return new Action(-1L, System.currentTimeMillis(), type,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:diamond", null, 1, false, null,
                null, null, null, null, null, null, new byte[]{1}, null, pairId, 5);
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

    @Test
    void terminationListenerWaitsForBlockedSinkBeforeDependentResourcesMayClose() throws Exception {
        BlockingSink sink = new BlockingSink();
        AtomicBoolean terminated = new AtomicBoolean();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                8, 25L, 1, sink, DAEMON, (Path) null, () -> terminated.set(true));
        try {
            q.submit(action(123));
            assertThat(sink.entered.await(2, TimeUnit.SECONDS)).isTrue();

            q.drainAndFlush(25L);
            assertThat(terminated).as("dependent resources must stay open while sink owns a batch")
                    .isFalse();

            sink.release.countDown();
            assertThat(q.awaitWorkerTermination(2_000L)).isTrue();
            assertThat(terminated).isTrue();
        } finally {
            sink.release.countDown();
            if (!q.isWorkerTerminated()) {
                q.drainAndFlush(2_000L);
            }
        }
    }

    @Test
    void admissionBoundaryOrdersLateProducerBeforeResourceClosure() throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicBoolean terminated = new AtomicBoolean();
        List<Action> seen = new CopyOnWriteArrayList<>();
        BatchSink sink = batch -> seen.addAll(batch);
        Runnable probe = () -> {
            probeEntered.countDown();
            try {
                assertThat(releaseProbe.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                8, 25L, 1, sink, DAEMON, (QuarantineStore) null,
                () -> terminated.set(true), probe);
        AtomicBoolean accepted = new AtomicBoolean();
        Thread producer = new Thread(() -> accepted.set(q.submit(action(321))), "vg-late-producer-test");
        Thread stopper = new Thread(() -> q.drainAndFlush(2_000L), "vg-stop-test");
        try {
            producer.start();
            assertThat(probeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            stopper.start();
            Thread.sleep(50L);
            assertThat(stopper.isAlive()).as("shutdown must wait for in-flight admission").isTrue();
            releaseProbe.countDown();
            producer.join(2_000L);
            stopper.join(2_000L);

            assertThat(accepted).isTrue();
            assertThat(seen).extracting(Action::x).containsExactly(321);
            assertThat(terminated).isTrue();
        } finally {
            releaseProbe.countDown();
            producer.join(2_000L);
            if (stopper.isAlive()) {
                q.drainAndFlush(2_000L);
                stopper.join(2_000L);
            }
            if (!q.isWorkerTerminated()) {
                q.close();
            }
        }
    }

    @Test
    void unexpectedWorkerExitPublishesAdmissionClosureBeforeFinalDrain() throws Exception {
        CountDownLatch finalSinkEntered = new CountDownLatch(1);
        CountDownLatch releaseFinalSink = new CountDownLatch(1);
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicBoolean terminated = new AtomicBoolean();
        BatchSink sink = batch -> {
            if (sinkCalls.incrementAndGet() == 1) {
                throw new AssertionError("simulated worker exit");
            }
            finalSinkEntered.countDown();
            releaseFinalSink.await(5, TimeUnit.SECONDS);
            throw new AssertionError("simulated final-drain failure");
        };
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                8, 25L, 1, sink, DAEMON, (Path) null, () -> terminated.set(true));
        try {
            assertThat(q.submit(action(321))).isTrue();
            assertThat(finalSinkEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(q.submit(action(322))).isFalse();
            releaseFinalSink.countDown();
            assertThat(q.awaitWorkerTermination(5_000L)).isTrue();
            assertThat(terminated).isTrue();
        } finally {
            releaseFinalSink.countDown();
            if (!q.isWorkerTerminated()) {
                q.close();
            }
        }
    }

    @Test
    void pauseThenShutdownFlushesLocalBatchAndQueuedTail() throws Exception {
        CapturingSink sink = new CapturingSink(2);
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 5_000L, 8, sink, DAEMON);
        try {
            q.submit(action(800));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (q.depth() != 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(q.depth()).as("first action must be held by worker batch").isZero();

            q.setPaused(true);
            q.submit(action(801));
            q.drainAndFlush(2_000L);

            assertThat(sink.latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(sink.seen).extracting(Action::x).containsExactly(800, 801);
            assertThat(q.permanentlyDropped()).isZero();
        } finally {
            q.close();
        }
    }

    @Test
    void successfulSinkDoesNotReflushWhenAckFailsTransiently() throws Exception {
        Path dir = Files.createTempDirectory("vg-ack-defer-test");
        Path journal = dir.resolve("quarantine.bin");

        AlwaysFailingSink failing = new AlwaysFailingSink();
        BatchedAsyncWriteQueue first = new BatchedAsyncWriteQueue(16, 25L, 4, failing, DAEMON, journal);
        first.setPaused(true);
        first.submit(action(910));
        first.drainAndFlush(5_000L);
        assertThat(first.quarantined()).isEqualTo(1L);

        AtomicInteger flushes = new AtomicInteger();
        BatchSink recovering = batch -> flushes.incrementAndGet();
        TransientAckStore ackFailsOnce = new TransientAckStore(journal);
        BatchedAsyncWriteQueue second = new BatchedAsyncWriteQueue(
                16, 25L, 4, recovering, DAEMON, ackFailsOnce);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6L);
            while (second.recoveredFromQuarantine() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(second.recoveredFromQuarantine()).isEqualTo(1L);
            assertThat(flushes.get())
                    .as("successful sink must not be repeated while only ACK is outstanding")
                    .isEqualTo(1);
            assertThat(second.quarantined()).isZero();
            assertThat(ackFailsOnce.ackAttempts()).isGreaterThanOrEqualTo(2);
        } finally {
            second.close();
        }
    }

    @Test
    void durableSinkSuccessSkipsReflushAfterRestartAndOnlyAcks() throws Exception {
        Path dir = Files.createTempDirectory("vg-durable-sink-success");
        Path journal = dir.resolve("quarantine.bin");

        // Seed an ADD, then durable-mark sink success without ACK.
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        long seq = seed.append(action(920));
        assertThat(seq).isPositive();
        seed.markSinkSucceeded(seq);
        assertThat(seed.entries()).singleElement()
                .extracting(QuarantineStore.Entry::sinkSucceeded).isEqualTo(true);

        AtomicInteger flushes = new AtomicInteger();
        BatchSink sink = batch -> flushes.incrementAndGet();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(16, 25L, 4, sink, DAEMON, journal);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4L);
            while (q.recoveredFromQuarantine() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(q.recoveredFromQuarantine())
                    .as("queue recovery must ACK a durable sink-success entry")
                    .isEqualTo(1L);
            assertThat(flushes.get())
                    .as("restart must not re-flush when SINK_SUCCEEDED is durable")
                    .isZero();
            assertThat(q.quarantined()).isZero();
        } finally {
            q.close();
        }

        QuarantineStore after = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(after.entries()).isEmpty();
    }

    private static final class TransientAckStore extends QuarantineStore {
        private final AtomicInteger ackAttempts = new AtomicInteger();

        TransientAckStore(Path path) throws Exception {
            super(path, 8, 1_000_000L);
        }

        int ackAttempts() {
            return ackAttempts.get();
        }

        @Override
        synchronized void acknowledge(long sequence) throws java.io.IOException {
            if (ackAttempts.incrementAndGet() == 1) {
                throw new java.io.IOException("controlled transient ACK failure");
            }
            super.acknowledge(sequence);
        }
    }

    /**
     * Proves marker retry with sinkSucceeded kept true (no reflush) across a simulated
     * restart boundary where only ADD is durable.
     */
    private static final class ControllableMarkerStore extends QuarantineStore {
        private final AtomicInteger markerAttempts = new AtomicInteger();
        private volatile boolean allow;

        ControllableMarkerStore(Path path) throws Exception {
            super(path, 8, 1_000_000L);
        }

        void allowMarkers() {
            allow = true;
        }

        int markerAttempts() {
            return markerAttempts.get();
        }

        @Override
        synchronized void markSinkSucceeded(long sequence) throws java.io.IOException {
            markerAttempts.incrementAndGet();
            if (!allow) {
                throw new java.io.IOException("controlled marker failure");
            }
            super.markSinkSucceeded(sequence);
        }
    }

    @Test
    void markerFailureRetriesWithoutReflushAndSurvivesRestartBoundary() throws Exception {
        Path dir = Files.createTempDirectory("vg-marker-retry");
        Path journal = dir.resolve("quarantine.bin");

        // Seed durable ADD only (no SINK_SUCCEEDED) — crash boundary mid-recovery.
        QuarantineStore seed = new QuarantineStore(journal, 8, 1_000_000L);
        long seq = seed.append(action(921));
        assertThat(seq).isPositive();
        assertThat(seed.entries()).singleElement()
                .extracting(QuarantineStore.Entry::sinkSucceeded).isEqualTo(false);

        ControllableMarkerStore failingMarkers = new ControllableMarkerStore(journal);
        AtomicInteger flushes = new AtomicInteger();
        BatchSink sink = batch -> flushes.incrementAndGet();
        BatchedAsyncWriteQueue first = new BatchedAsyncWriteQueue(16, 25L, 4, sink, DAEMON, failingMarkers);
        try {
            // Wait until sink flush has succeeded and at least one marker attempt failed.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((flushes.get() < 1 || failingMarkers.markerAttempts() < 1)
                    && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(flushes.get())
                    .as("recovery must flush once before marker")
                    .isEqualTo(1);
            assertThat(failingMarkers.markerAttempts())
                    .as("marker persistence must be attempted and fail closed")
                    .isGreaterThanOrEqualTo(1);
            assertThat(first.recoveredFromQuarantine())
                    .as("must not ACK/recover before marker is durable")
                    .isZero();

            // Allow markers; next due pass retries markSinkSucceeded (backoff starts at 2s).
            failingMarkers.allowMarkers();
            long recoverDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8L);
            while (first.recoveredFromQuarantine() == 0L && System.nanoTime() < recoverDeadline) {
                Thread.sleep(50L);
            }
            assertThat(first.recoveredFromQuarantine())
                    .as("marker retry must succeed then ACK without a second sink flush")
                    .isEqualTo(1L);
            assertThat(flushes.get())
                    .as("must not reflush while only marker was outstanding")
                    .isEqualTo(1);
            assertThat(failingMarkers.markerAttempts())
                    .as("marker must be retried after the controlled failure")
                    .isGreaterThanOrEqualTo(2);
            assertThat(first.quarantined()).isZero();
        } finally {
            first.close();
        }

        QuarantineStore afterLive = new QuarantineStore(journal, 8, 1_000_000L);
        assertThat(afterLive.entries()).isEmpty();

        // Simulated restart boundary with only ADD durable (marker never written).
        Path journal2 = dir.resolve("quarantine-restart.bin");
        QuarantineStore seed2 = new QuarantineStore(journal2, 8, 1_000_000L);
        long seq2 = seed2.append(action(922));
        assertThat(seq2).isPositive();

        ControllableMarkerStore restartFail = new ControllableMarkerStore(journal2);
        AtomicInteger flushesRestartPhase = new AtomicInteger();
        BatchedAsyncWriteQueue crashMid = new BatchedAsyncWriteQueue(
                16, 25L, 4, batch -> flushesRestartPhase.incrementAndGet(), DAEMON, restartFail);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((flushesRestartPhase.get() < 1 || restartFail.markerAttempts() < 1)
                    && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(flushesRestartPhase.get()).isEqualTo(1);
            assertThat(restartFail.markerAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(crashMid.recoveredFromQuarantine()).isZero();
        } finally {
            crashMid.close();
        }

        QuarantineStore mid = new QuarantineStore(journal2, 8, 1_000_000L);
        assertThat(mid.entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.sequence()).isEqualTo(seq2);
                    assertThat(e.sinkSucceeded())
                            .as("failed marker must leave only ADD durable across restart")
                            .isFalse();
                });

        // Fresh queue after restart: will reflush once (no durable marker), then
        // marker succeeds, then ACK.
        ControllableMarkerStore okMarkers = new ControllableMarkerStore(journal2);
        okMarkers.allowMarkers();
        AtomicInteger flushesAfterRestart = new AtomicInteger();
        BatchSink sink2 = batch -> flushesAfterRestart.incrementAndGet();
        BatchedAsyncWriteQueue second = new BatchedAsyncWriteQueue(16, 25L, 4, sink2, DAEMON, okMarkers);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (second.recoveredFromQuarantine() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(second.recoveredFromQuarantine()).isEqualTo(1L);
            assertThat(flushesAfterRestart.get())
                    .as("restart without durable marker requires exactly one reflush")
                    .isEqualTo(1);
            assertThat(okMarkers.markerAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(second.quarantined()).isZero();
        } finally {
            second.close();
        }

        QuarantineStore after = new QuarantineStore(journal2, 8, 1_000_000L);
        assertThat(after.entries()).isEmpty();
    }

    @Test
    void oversizedQuarantineAppendMarksRetentionLimitReached() throws Exception {
        Path dir = Files.createTempDirectory("vg-quarantine-byte-cap");
        Path journal = dir.resolve("quarantine.bin");
        long tinyCap = 200L;

        QuarantineStore store = new QuarantineStore(journal, 8, tinyCap);
        byte[] huge = new byte[512];
        Action oversized = new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                930, 64, 0, "minecraft:stone", null, 1, false, null,
                null, null, null, null, null,
                huge, null, null);

        AlwaysFailingSink failing = new AlwaysFailingSink();
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(8, 25L, 4, failing, DAEMON, store);
        try {
            q.setPaused(true);
            q.submit(oversized);
            q.drainAndFlush(5_000L);

            assertThat(store.append(oversized))
                    .as("direct store path also fails closed for oversized frame")
                    .isEqualTo(-1L);
            assertThat(Files.exists(journal) ? Files.size(journal) : 0L)
                    .isLessThanOrEqualTo(tinyCap);
            assertThat(store.entries()).isEmpty();
            assertThat(q.quarantineRetentionLimitReached())
                    .as("queue/status path marks retention limit reached on -1 append")
                    .isTrue();
            assertThat(q.quarantineOverflow()).isGreaterThanOrEqualTo(1L);
            assertThat(q.permanentlyDropped()).isGreaterThanOrEqualTo(1L);
            assertThat(q.quarantined()).isZero();
        } finally {
            q.close();
        }
    }
}
