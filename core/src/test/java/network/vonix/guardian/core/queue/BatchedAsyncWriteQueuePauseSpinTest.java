package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paused worker must not busy-wait.
 *
 * <p>The paused branch slept for {@code pollMs}, which is derived from the flush
 * window. The window does not advance while paused, so after one
 * {@code flushIntervalMs} the remaining slice went negative and {@code pollMs}
 * collapsed to its 1ms floor: roughly 1000 wakeups per second, for as long as the
 * pause lasts. {@code /vg consumer pause} is a maintenance-window command, so that
 * spin covers the whole outage.</p>
 *
 * <p>Measured via the {@link AsyncWriteQueue#setPaused(boolean)} sleep itself: the
 * queue's worker is the only thread calling {@code Thread.sleep} with this name, so
 * counting scheduler entries is not needed &mdash; a bounded number of context
 * switches on the worker thread over a fixed wall-clock window is enough to separate
 * "sleeps for the interval" from "wakes every millisecond". This is a behavioural
 * bound on wall-clock time, not a benchmark of throughput.</p>
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
    void pausedWorkerDoesNotWakeEveryMillisecond() throws Exception {
        // 200ms flush interval. Under the old paused loop pollMs equalled 200 on the
        // first iteration, then collapsed to its 1ms floor and stayed there, because
        // the flush window it is derived from never advances while paused.
        ThreadFactory tracking = r -> {
            Thread t = new Thread(r, "vg-pause-spin-measured");
            t.setDaemon(true);
            return t;
        };
        BatchedAsyncWriteQueue q = new BatchedAsyncWriteQueue(
                64, 200L, 8, batch -> { }, tracking);
        try {
            q.setPaused(true);
            assertThat(q.submit(action(1))).isTrue();

            Thread worker = findWorker("vg-pause-spin-measured");
            assertThat(worker).as("queue worker thread must exist").isNotNull();

            // One full interval of lead-in so the old loop has certainly passed the
            // point where pollMs collapsed, then measure the next interval.
            Thread.sleep(200L);
            long cpuBefore = cpuNanos(worker);
            Thread.sleep(400L);   // two flush windows of measured wall time
            long cpuAfter = cpuNanos(worker);

            if (cpuBefore < 0L || cpuAfter < 0L) {
                // Per-thread CPU accounting is unavailable in this JVM; a timing
                // assertion here would be a guess, so say so instead of pretending.
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "per-thread CPU time is unavailable; pause-spin bound not measured");
            }

            long busyMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
            // Old loop: 1ms wakeups for 400ms (~400 wakeups) burned a large share of the
            // wall clock as CPU on this thread. New loop: two 200ms sleeps (~2 wakeups).
            // The bound is loose so a loaded CI host cannot flake it while still failing
            // a millisecond spin.
            assertThat(busyMillis)
                    .as("a paused worker must sleep, not wake every millisecond "
                            + "(worker CPU over 400ms wall = %dms)", busyMillis)
                    .isLessThan(150L);
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

    private static Thread findWorker(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals(name) && t.isAlive()) {
                    return t;
                }
            }
            Thread.sleep(5L);
        }
        return null;
    }

    /** CPU time consumed by {@code thread}, via the JVM's per-thread MXBean. */
    private static long cpuNanos(Thread thread) {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!mx.isThreadCpuTimeSupported()) {
            return -1L;
        }
        if (!mx.isThreadCpuTimeEnabled()) {
            try {
                mx.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException unsupported) {
                return -1L;
            }
        }
        try {
            // Throws ThreadDeath/IllegalArgumentException once the thread is gone;
            // -1 makes the caller skip the timing assertion rather than fail.
            long cpu = mx.getThreadCpuTime(thread.getId());
            return cpu < 0L ? -1L : cpu;
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }
}
