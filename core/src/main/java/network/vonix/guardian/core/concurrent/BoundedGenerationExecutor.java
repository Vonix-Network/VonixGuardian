package network.vonix.guardian.core.concurrent;

import java.util.Objects;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A bounded executor with an explicit server-generation boundary.
 *
 * <p>{@link #reset(Runnable)} closes admission before shutdown, waits for the
 * old generation's workers, and only then invokes the deferred callback and
 * creates the next generation. If interruption cannot terminate the old
 * generation, one daemon watcher waits for it; no replacement generation is
 * opened in the meantime.</p>
 */
public final class BoundedGenerationExecutor implements Executor {
    private static final Logger LOG = Logger.getLogger(BoundedGenerationExecutor.class.getName());
    private static final long DEFAULT_STOP_WAIT_MILLIS = 2_000L;

    private final Object lifecycleLock = new Object();
    private final String threadName;
    private final int queueCapacity;
    private final int workerCount;
    private final long stopWaitMillis;
    private final ThreadFactory threadFactory;

    private ThreadPoolExecutor executor;
    private boolean accepting = true;
    private boolean completionInProgress;
    private Thread terminationWatcher;
    private final ArrayDeque<Runnable> deferredTerminationCallbacks = new ArrayDeque<>();

    public BoundedGenerationExecutor(String threadName, int queueCapacity) {
        this(threadName, queueCapacity, 1, DEFAULT_STOP_WAIT_MILLIS);
    }

    /** Constructor with a short wait bound for deterministic lifecycle tests. */
    public BoundedGenerationExecutor(String threadName, int queueCapacity, long stopWaitMillis) {
        this(threadName, queueCapacity, 1, stopWaitMillis);
    }

    /**
     * Constructor for bounded multi-worker callers that still need generation
     * fencing. The two-worker command executor uses this overload so its prior
     * command concurrency is preserved while shutdown remains callback-safe.
     */
    public BoundedGenerationExecutor(String threadName, int queueCapacity, int workerCount) {
        this(threadName, queueCapacity, workerCount, DEFAULT_STOP_WAIT_MILLIS);
    }

    private BoundedGenerationExecutor(String threadName,
                                      int queueCapacity,
                                      int workerCount,
                                      long stopWaitMillis) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be positive");
        if (stopWaitMillis < 1L) throw new IllegalArgumentException("stopWaitMillis must be positive");
        this.queueCapacity = queueCapacity;
        this.workerCount = workerCount;
        this.stopWaitMillis = stopWaitMillis;
        AtomicInteger sequence = new AtomicInteger();
        this.threadFactory = task -> {
            Thread thread = new Thread(task, threadName + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = newExecutor();
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (lifecycleLock) {
            if (!accepting) {
                throw new RejectedExecutionException(threadName + " is closed for this server generation");
            }
            executor.execute(command);
        }
    }

    /**
     * Closes this generation and arranges the callback to run only after the
     * worker has actually terminated. A false result means a watcher owns the
     * eventual callback and no replacement generation is accepting work yet.
     */
    public boolean reset(Runnable afterTermination) {
        ThreadPoolExecutor old;
        synchronized (lifecycleLock) {
            if (afterTermination != null) {
                deferredTerminationCallbacks.addLast(afterTermination);
            }
            if (completionInProgress) {
                return false;
            }
            accepting = false;
            old = executor;
            old.shutdown();
        }

        boolean terminated = await(old, stopWaitMillis);
        if (!terminated) {
            old.shutdownNow();
            terminated = await(old, stopWaitMillis);
        }
        if (terminated) {
            completeGeneration(old);
        } else {
            arrangeTerminationWatcher(old);
        }
        return terminated;
    }

    public boolean isAccepting() {
        synchronized (lifecycleLock) {
            return accepting;
        }
    }

    public boolean isTerminated() {
        synchronized (lifecycleLock) {
            return executor.isTerminated();
        }
    }

    private ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private boolean await(ThreadPoolExecutor pool, long millis) {
        try {
            return pool.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return pool.isTerminated();
        }
    }

    private void arrangeTerminationWatcher(ThreadPoolExecutor old) {
        boolean completeNow = false;
        synchronized (lifecycleLock) {
            if (executor != old) {
                return;
            }
            if (old.isTerminated()) {
                completeNow = true;
            } else {
                if (terminationWatcher != null && terminationWatcher.isAlive()) return;
                Thread watcher = new Thread(() -> {
                    try {
                        old.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                        if (old.isTerminated()) completeGeneration(old);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        LOG.log(Level.WARNING, threadName + " termination watcher interrupted; generation remains closed", ex);
                    }
                }, threadName + "-shutdown-watcher");
                watcher.setDaemon(true);
                terminationWatcher = watcher;
                watcher.start();
            }
        }
        if (completeNow) {
            completeGeneration(old);
        }
    }

    private void completeGeneration(ThreadPoolExecutor old) {
        synchronized (lifecycleLock) {
            if (executor != old || !old.isTerminated()) return;
            if (completionInProgress) return;
            completionInProgress = true;
        }
        for (;;) {
            Runnable callback;
            synchronized (lifecycleLock) {
                callback = deferredTerminationCallbacks.pollFirst();
                if (callback == null) {
                    if (executor == old && old.isTerminated()) {
                        executor = newExecutor();
                        accepting = true;
                        completionInProgress = false;
                        terminationWatcher = null;
                    }
                    return;
                }
            }
            try {
                callback.run();
            } catch (Throwable failure) {
                LOG.log(Level.SEVERE, threadName + " termination callback failed", failure);
            }
        }
    }
}
