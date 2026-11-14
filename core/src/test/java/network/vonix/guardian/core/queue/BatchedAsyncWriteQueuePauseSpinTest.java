package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paused worker must not busy-wait.
 *
 * <p>The paused branch slept for {@code pollMs}, which is derived from the flush
 * window. That window does not advance while paused: on the first paused iteration
 * {@code pollMs} equals the flush interval, but once the interval has elapsed the
 * remaining slice goes negative and {@code pollMs} collapses to its 1ms floor &mdash;
 * about 1000 wakeups per second for the rest of the pause.
 * {@code /vg consumer pause} is a maintenance command, so that spin runs for the whole
 * outage.</p>
 *
 * <p>Measured by counting paused-loop iterations through the package-private
 * observation seam over a fixed wall-clock window. Counting iterations is the direct
 * measure of the defect; a CPU-time bound would not separate the two implementations,
 * because both spend the vast majority of their time blocked in {@code Thread.sleep}
 * &mdash; the difference is one wakeup per millisecond versus one per flush interval.</p>
 */
class BatchedAsyncWriteQueuePauseSpinTest {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "vg-pause-spin-test");
        t.setDaemon(true);
        return t;
    };

    private static Action action(int x) {
        return new Action(-1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE,
                UUID.randomUUID(), "tester", "minecraft:overworld",
                x, 64, 0, "minecraft:stone", null, 1, false, null);
    }

    @Test
    void pausedWorkerOneIterationPerIntervalNotPerMillisecond() throws Exception {
        final long flushIntervalMs = 200L;
        AtomicInteger pausedIterations = new AtomicInteger();
        CountDownLatch parked = new CountDownLatch(1);

        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                64, flushIntervalMs, 8, batch -> { }, DAEMON, (QuarantineStore) null,
                null, null, null, null, null,
                () -> {
                    if (pausedIterations.incrementAndGet() == 1) {
                        parked.countDown();
                    }
                });
        try {
            q.setPaused(true);
            assertThat(parked.await(2, TimeUnit.SECONDS))
                    .as("worker must enter the paused loop")
                    .isTrue();

            // Measure a window in which the OLD loop has certainly passed the point
            // where pollMs collapsed to its 1ms floor.
            int before = pausedIterations.get();
            Thread.sleep(flushIntervalMs);
            int baseline = pausedIterations.get();
            Thread.sleep(400L);   // two flush intervals of measured wall time
            int iterations = pausedIterations.get() - baseline;

            // Old loop: ~400 iterations (one per millisecond). New loop: ~2.
            assertThat(iterations)
                    .as("a paused worker must wake per interval, not per millisecond "
                            + "(iterations over 400ms = %d)", iterations)
                    .isLessThan(50);
            assertThat(pausedIterations.get())
                    .as("the paused loop must still be running")
                    .isGreaterThan(before);
        } finally {
            q.close();
        }
    }

    @Test
    void resumeTakesEffectImmediatelyRatherThanAtTheNextInterval() throws Exception {
        // A long flush interval: without a wake on unpause, the resume would only be
        // observed whenever the paused sleep expires.
        CountDownLatch flushed = new CountDownLatch(1);
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                64, 30_000L, 8, batch -> flushed.countDown(), DAEMON);
        try {
            q.setPaused(true);
            assertThat(q.submit(action(7))).isTrue();
            Thread.sleep(100L);

            q.setPaused(false);
            assertThat(flushed.await(2, TimeUnit.SECONDS))
                    .as("resume must wake the worker, not wait out the flush interval")
                    .isTrue();
        } finally {
            q.close();
        }
    }

    @Test
    void shutdownWhilePausedStillDrainsRatherThanWaitingOutTheSleep() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                64, 30_000L, 8, batch -> flushed.countDown(), DAEMON);
        try {
            q.setPaused(true);
            assertThat(q.submit(action(9))).isTrue();
            Thread.sleep(100L);

            q.drainAndFlush(3_000L);
            assertThat(flushed.await(2, TimeUnit.SECONDS))
                    .as("shutdown must interrupt the paused sleep, not wait 30s for it")
                    .isTrue();
        } finally {
            q.close();
        }
    }
}
