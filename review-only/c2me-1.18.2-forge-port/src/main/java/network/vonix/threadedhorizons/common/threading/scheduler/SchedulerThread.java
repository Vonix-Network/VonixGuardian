package network.vonix.threadedhorizons.common.threading.scheduler;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class SchedulerThread extends Thread implements Executor {

    static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Scheduler");

    public static SchedulerThread INSTANCE = new SchedulerThread();

    private static final ThreadLocal<int[]> NESTED_GENERATION = ThreadLocal.withInitial(() -> new int[1]);

    private final ConcurrentLinkedQueue<Runnable> rawTasks = new ConcurrentLinkedQueue<>();
    private final PriorityBlockingQueue<SchedulingAsyncCombinedLock<?>> pendingLocks = new PriorityBlockingQueue<>();
    private final Set<SchedulingAsyncCombinedLock<?>> admittedLocks = ConcurrentHashMap.newKeySet();

    private final Semaphore semaphore = new Semaphore(ThreadedHorizonsConfig.globalExecutorParallelism);
    private final int pipelineLimit = computePipelineLimit(
            ThreadedHorizonsConfig.globalExecutorParallelism,
            Runtime.getRuntime().maxMemory());
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final ReentrantLock pipelineLock = new ReentrantLock();
    private final Condition pipelineNotFull = this.pipelineLock.newCondition();
    private final LongOpenHashSet pipelineChunks = new LongOpenHashSet();
    private final ArrayDeque<PipelineAdmission> pipelineWaiters = new ArrayDeque<>();

    private long lastRebuild = System.currentTimeMillis();
    private final AtomicBoolean hasPriorityChanges = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private SchedulerThread() {
        this.setName("Threaded Horizons scheduler");
        this.setDaemon(false);
        this.start();
    }

    @Override
    public void run() {
        while (true) {
            boolean didWork = false;

            if (doPriorityChanges()) didWork = true;

            while (!this.stopping.get() && !pendingLocks.isEmpty() && semaphore.tryAcquire()) {
                SchedulingAsyncCombinedLock<?> lock = pendingLocks.poll();
                if (lock != null && lock.tryAcquire()) {
                    lock.doAction(semaphore::release);
                    didWork = true;
                } else {
                    semaphore.release();
                }
            }

            if (!didWork) {
                Runnable runnable = rawTasks.poll();
                if (runnable != null) {
                    didWork = true;
                    try {
                        runnable.run();
                    } catch (Throwable t) {
                        LOGGER.error("Scheduler task failed", t);
                    }
                }
            }

            if (this.stopping.get()) {
                this.lifecycleLock.lock();
                try {
                    drainRawTasks();
                    failRemainingLocks();
                } finally {
                    this.lifecycleLock.unlock();
                }
                break;
            }

            if (!didWork) {
                LockSupport.parkNanos("Waiting for tasks", 10_000_000);
            }
        }
    }

    public boolean addPendingLock(SchedulingAsyncCombinedLock<?> lock) {
        if (this.stopping.get()) {
            failLock(lock);
            return false;
        }
        awaitShutdownAdmissionFence();
        this.lifecycleLock.lock();
        try {
            if (this.stopping.get()) {
                failLock(lock);
                return false;
            }
            this.admittedLocks.add(lock);
            lock.getFuture().whenComplete((result, throwable) -> this.admittedLocks.remove(lock));
            this.pendingLocks.add(lock);
        } finally {
            this.lifecycleLock.unlock();
        }
        LockSupport.unpark(this);
        return true;
    }

    public void shutdown() {
        List<PipelineAdmission> cancelled = new ArrayList<>();
        this.lifecycleLock.lock();
        try {
            this.stopping.set(true);
            this.pipelineLock.lock();
            try {
                drainWaitersLocked(cancelled);
                this.pipelineNotFull.signalAll();
            } finally {
                this.pipelineLock.unlock();
            }
            failRemainingLocks();
        } finally {
            this.lifecycleLock.unlock();
        }
        failAdmissions(cancelled);
        LockSupport.unpark(this);
    }

    public boolean isStopping() {
        return this.stopping.get();
    }

    public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        long millis = unit.toMillis(timeout);
        this.join(millis);
        return !this.isAlive();
    }

    public static synchronized void replaceInstance() {
        SchedulerThread previous = INSTANCE;
        previous.shutdown();
        try {
            previous.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        INSTANCE = new SchedulerThread();
    }

    private boolean doPriorityChanges() {
        final long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis > lastRebuild + 500) { // at most twice a second
            lastRebuild = currentTimeMillis;
            if (this.hasPriorityChanges.compareAndSet(true, false)) {
                final long startTime = System.nanoTime();
                final int size = this.pendingLocks.size();
                ArrayList<SchedulingAsyncCombinedLock<?>> tmp = new ArrayList<>(size);
                // re-add locks to reflect priority changes
                this.pendingLocks.drainTo(tmp);
                this.pendingLocks.addAll(tmp);
//                System.out.printf("Did priority changes for %d entries in %.2fms\n", size, (System.nanoTime() - startTime) / 1_000_000.0);
                return true;
            }
        }
        return false;
    }

    public void notifyPriorityChange() {
        this.hasPriorityChanges.set(true);
    }

    public int getPendingLockCount() {
        return this.pendingLocks.size();
    }

    /**
     * Maximum unique chunks allowed in the threaded-worldgen pipeline at once.
     * Scales with heap so {@code -Xmx2G} cannot retain a full spawn-wave of
     * NoiseChunk/protochunk state, while still admitting several chunks per worker.
     */
    public static int computePipelineLimit(int parallelism, long maxMemoryBytes) {
        int workers = Math.max(1, parallelism);
        if (maxMemoryBytes < 1L) {
            maxMemoryBytes = 1L;
        }
        int byHeap = (int) Math.max(8L, maxMemoryBytes / (64L * 1024L * 1024L));
        int byParallel = Math.max(8, workers * 8);
        return Math.min(64, Math.max(byHeap, byParallel));
    }

    public int getPipelineLimit() {
        return this.pipelineLimit;
    }

    public int getPipelineCount() {
        this.pipelineLock.lock();
        try {
            return this.pipelineChunks.size();
        } finally {
            this.pipelineLock.unlock();
        }
    }

    public int getPipelineWaiterCount() {
        this.pipelineLock.lock();
        try {
            return this.pipelineWaiters.size();
        } finally {
            this.pipelineLock.unlock();
        }
    }

    public static void enterNestedGeneration() {
        NESTED_GENERATION.get()[0]++;
    }

    public static void leaveNestedGeneration() {
        int[] depth = NESTED_GENERATION.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    public void enterChunkPipeline(ChunkPos pos) throws InterruptedException {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        if (Thread.currentThread() == this) {
            throw new IllegalStateException("scheduler thread cannot block on worldgen admission");
        }
        long key = pos.toLong();
        boolean nested = NESTED_GENERATION.get()[0] > 0;
        while (true) {
            this.pipelineLock.lock();
            try {
                if (this.pipelineChunks.contains(key)) {
                    return;
                }
                if (this.stopping.get()) {
                    throw new IllegalStateException("scheduler is stopping");
                }
                if (nested || this.pipelineChunks.size() < this.pipelineLimit) {
                    this.pipelineChunks.add(key);
                    return;
                }
            } finally {
                this.pipelineLock.unlock();
            }
            parkForPipelineSlot();
        }
    }

    /**
     * Non-blocking admission used by chunk generation. The caller receives a
     * completed future when the chunk already holds a slot, a slot is free, or
     * nested generation is allowed to exceed the limit. Otherwise the request
     * is queued and completed when {@link #leaveChunkPipeline} frees capacity.
     */
    public CompletableFuture<Void> acquireChunkPipelineAsync(ChunkPos pos) {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        this.pipelineLock.lock();
        try {
            if (this.stopping.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("scheduler is stopping"));
            }
            long key = pos.toLong();
            if (this.pipelineChunks.contains(key)
                    || NESTED_GENERATION.get()[0] > 0
                    || this.pipelineChunks.size() < this.pipelineLimit) {
                this.pipelineChunks.add(key);
                return CompletableFuture.completedFuture(null);
            }
            PipelineAdmission waiter = new PipelineAdmission(pos);
            this.pipelineWaiters.addLast(waiter);
            return waiter.future;
        } finally {
            this.pipelineLock.unlock();
        }
    }

    public void leaveChunkPipeline(ChunkPos pos) {
        List<PipelineAdmission> released = new ArrayList<>();
        boolean stopping;
        this.pipelineLock.lock();
        try {
            if (pos != null) {
                this.pipelineChunks.remove(pos.toLong());
            }
            stopping = this.stopping.get();
            if (stopping) {
                drainWaitersLocked(released);
            } else {
                admitWaitersLocked(released);
            }
            this.pipelineNotFull.signalAll();
        } finally {
            this.pipelineLock.unlock();
        }
        if (stopping) {
            failAdmissions(released);
        } else {
            completeAdmissions(released);
        }
    }

    private void parkForPipelineSlot() throws InterruptedException {
        if (ForkJoinTask.inForkJoinPool()) {
            ForkJoinPool.managedBlock(new PipelineSlotBlocker());
            return;
        }
        this.pipelineLock.lock();
        try {
            if (this.stopping.get() || this.pipelineChunks.size() < this.pipelineLimit) {
                return;
            }
            this.pipelineNotFull.await(50L, TimeUnit.MILLISECONDS);
        } finally {
            this.pipelineLock.unlock();
        }
    }

    volatile CountDownLatch shutdownAdmissionPassedStopCheck;
    volatile CountDownLatch shutdownAdmissionAllowEnqueue;

    private void awaitShutdownAdmissionFence() {
        CountDownLatch passed = this.shutdownAdmissionPassedStopCheck;
        CountDownLatch allow = this.shutdownAdmissionAllowEnqueue;
        if (passed == null || allow == null) {
            return;
        }
        passed.countDown();
        try {
            allow.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainRawTasks() {
        Runnable runnable;
        while ((runnable = this.rawTasks.poll()) != null) {
            try {
                runnable.run();
            } catch (Throwable t) {
                LOGGER.error("Scheduler task failed", t);
            }
        }
    }

    private void failRemainingLocks() {
        SchedulingAsyncCombinedLock<?> queued;
        while ((queued = this.pendingLocks.poll()) != null) {
            failLock(queued);
        }
        for (SchedulingAsyncCombinedLock<?> admitted : this.admittedLocks) {
            failLock(admitted);
        }
        this.admittedLocks.clear();
    }

    private static void failLock(SchedulingAsyncCombinedLock<?> lock) {
        if (lock != null && !lock.getFuture().isDone()) {
            lock.getFuture().completeExceptionally(new IllegalStateException("scheduler is stopping"));
        }
    }

    private void admitWaitersLocked(List<PipelineAdmission> admitted) {
        while (!this.pipelineWaiters.isEmpty()) {
            PipelineAdmission waiter = this.pipelineWaiters.peekFirst();
            long key = waiter.pos.toLong();
            if (this.pipelineChunks.contains(key) || this.pipelineChunks.size() < this.pipelineLimit) {
                this.pipelineWaiters.pollFirst();
                this.pipelineChunks.add(key);
                admitted.add(waiter);
            } else {
                return;
            }
        }
    }

    private void drainWaitersLocked(List<PipelineAdmission> waiters) {
        PipelineAdmission waiter;
        while ((waiter = this.pipelineWaiters.pollFirst()) != null) {
            waiters.add(waiter);
        }
    }

    private static void completeAdmissions(List<PipelineAdmission> waiters) {
        for (PipelineAdmission waiter : waiters) {
            waiter.future.complete(null);
        }
    }

    private static void failAdmissions(List<PipelineAdmission> waiters) {
        IllegalStateException stopping = new IllegalStateException("scheduler is stopping");
        for (PipelineAdmission waiter : waiters) {
            waiter.future.completeExceptionally(stopping);
        }
    }

    private static final class PipelineAdmission {
        private final ChunkPos pos;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private PipelineAdmission(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private final class PipelineSlotBlocker implements ForkJoinPool.ManagedBlocker {
        private boolean ready;

        @Override
        public boolean isReleasable() {
            if (this.ready) {
                return true;
            }
            pipelineLock.lock();
            try {
                this.ready = stopping.get() || pipelineChunks.size() < pipelineLimit;
                return this.ready;
            } finally {
                pipelineLock.unlock();
            }
        }

        @Override
        public boolean block() throws InterruptedException {
            pipelineLock.lock();
            try {
                if (stopping.get() || pipelineChunks.size() < pipelineLimit) {
                    this.ready = true;
                    return true;
                }
                pipelineNotFull.await(50L, TimeUnit.MILLISECONDS);
                this.ready = stopping.get() || pipelineChunks.size() < pipelineLimit;
                return this.ready;
            } finally {
                pipelineLock.unlock();
            }
        }
    }

    public int getRawTaskCount() {
        return this.rawTasks.size();
    }

    public int getAvailablePermits() {
        return this.semaphore.availablePermits();
    }

    @Override
    public void execute(@NotNull Runnable command) {
        this.lifecycleLock.lock();
        try {
            if (this.stopping.get()) {
                network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics.recordRejected();
                throw new IllegalStateException("scheduler is stopping");
            }
            this.rawTasks.add(command);
        } finally {
            this.lifecycleLock.unlock();
        }
        LockSupport.unpark(this);
    }
}
