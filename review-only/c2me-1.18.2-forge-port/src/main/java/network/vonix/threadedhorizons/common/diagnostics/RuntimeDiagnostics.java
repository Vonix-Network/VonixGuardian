package network.vonix.threadedhorizons.common.diagnostics;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local counters for disposable dedicated-server load gates.
 * Values are measurements, not performance claims.
 */
public final class RuntimeDiagnostics {
    private static final AtomicLong UNCAUGHT = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();

    private RuntimeDiagnostics() {
    }

    public static void recordUncaught(Throwable error) {
        UNCAUGHT.incrementAndGet();
    }

    public static void recordRejected() {
        REJECTED.incrementAndGet();
    }

    public static long uncaughtExceptions() {
        return UNCAUGHT.get();
    }

    public static long rejectedTasks() {
        return REJECTED.get();
    }

    public static Snapshot snapshot() {
        ForkJoinPool pool = GlobalExecutors.executor;
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        return new Snapshot(
                pool.getPoolSize(),
                pool.getActiveThreadCount(),
                pool.getRunningThreadCount(),
                pool.getQueuedTaskCount(),
                pool.getQueuedSubmissionCount(),
                pool.getStealCount(),
                pool.getParallelism(),
                scheduler.getPendingLockCount(),
                scheduler.getRawTaskCount(),
                scheduler.getAvailablePermits(),
                UNCAUGHT.get(),
                REJECTED.get()
        );
    }

    public static String formatLine() {
        Snapshot snap = snapshot();
        return String.format(
                "TH_STATUS pool=%d active=%d running=%d queuedTasks=%d queuedSubmission=%d steal=%d parallelism=%d schedulerPending=%d schedulerRaw=%d schedulerPermits=%d uncaught=%d rejected=%d",
                snap.poolSize,
                snap.activeThreads,
                snap.runningThreads,
                snap.queuedTasks,
                snap.queuedSubmissions,
                snap.stealCount,
                snap.parallelism,
                snap.schedulerPending,
                snap.schedulerRaw,
                snap.schedulerPermits,
                snap.uncaught,
                snap.rejected
        );
    }

    public record Snapshot(
            int poolSize,
            int activeThreads,
            int runningThreads,
            long queuedTasks,
            int queuedSubmissions,
            long stealCount,
            int parallelism,
            int schedulerPending,
            int schedulerRaw,
            int schedulerPermits,
            long uncaught,
            long rejected
    ) {
    }
}
