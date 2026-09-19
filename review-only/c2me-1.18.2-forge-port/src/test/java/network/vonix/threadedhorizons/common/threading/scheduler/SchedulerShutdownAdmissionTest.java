package network.vonix.threadedhorizons.common.threading.scheduler;

import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class SchedulerShutdownAdmissionTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        if (scheduler.shutdownAdmissionAllowEnqueue != null) {
            scheduler.shutdownAdmissionAllowEnqueue.countDown();
        }
        scheduler.shutdownAdmissionPassedStopCheck = null;
        scheduler.shutdownAdmissionAllowEnqueue = null;
        SchedulerThread.replaceInstance();
    }

    @Test
    void admissionThatPassesStopCheckAfterShutdownDrainsCannotRemainIncomplete() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        CountDownLatch passedStopCheck = new CountDownLatch(1);
        CountDownLatch allowEnqueue = new CountDownLatch(1);
        scheduler.shutdownAdmissionPassedStopCheck = passedStopCheck;
        scheduler.shutdownAdmissionAllowEnqueue = allowEnqueue;

        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(7, 7)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("late")
        );

        Thread submitter = new Thread(() -> scheduler.addPendingLock(lock), "shutdown-admission-race");
        try {
            submitter.start();
            assertTrue(passedStopCheck.await(5, TimeUnit.SECONDS), "submitter never reached the post-check enqueue window");

            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS), "scheduler did not exit after shutdown");

            allowEnqueue.countDown();
            submitter.join(5000L);
            assertFalse(submitter.isAlive(), "submitter stuck in addPendingLock");

            assertTrue(
                    lock.getFuture().isDone(),
                    "admission racing with shutdown left an incomplete future"
            );
            assertTrue(
                    lock.getFuture().isCompletedExceptionally(),
                    "post-drain admission must be rejected, not executed after scheduler exit"
            );
        } finally {
            allowEnqueue.countDown();
            submitter.join(1000L);
        }
    }

    @Test
    void shutdownFailsAlreadyPendingLocks() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        ChunkPos pos = new ChunkPos(3, 3);
        AsyncLock.LockToken held = named.acquireLock(pos).toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                    named,
                    Set.of(pos),
                    () -> 0,
                    scheduler,
                    () -> CompletableFuture.completedFuture("queued")
            );
            assertTrue(scheduler.addPendingLock(lock));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scheduler.getPendingLockCount() == 0 && !lock.getFuture().isDone() && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            scheduler.shutdown();
            assertTrue(lock.getFuture().isDone(), "pending lock was stranded across shutdown");
            assertTrue(lock.getFuture().isCompletedExceptionally());
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            held.releaseLock();
        }
    }

    @Test
    void shutdownFailsBlockedPipelineAdmissions() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int limit = scheduler.getPipelineLimit();
        for (int index = 0; index < limit; index++) {
            scheduler.enterChunkPipeline(new ChunkPos(index, 11));
        }
        List<CompletableFuture<Void>> waiters = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            waiters.add(scheduler.acquireChunkPipelineAsync(new ChunkPos(limit + index, 11)));
        }
        assertEquals(4, scheduler.getPipelineWaiterCount());
        scheduler.shutdown();
        for (CompletableFuture<Void> waiter : waiters) {
            assertTrue(waiter.isDone(), "blocked pipeline admission was stranded");
            assertTrue(waiter.isCompletedExceptionally());
        }
        assertEquals(0, scheduler.getPipelineWaiterCount());
    }

    @Test
    void racingWorldgenAdmissionReleasesPipelineSlot() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        CountDownLatch passedStopCheck = new CountDownLatch(1);
        CountDownLatch allowEnqueue = new CountDownLatch(1);
        scheduler.shutdownAdmissionPassedStopCheck = passedStopCheck;
        scheduler.shutdownAdmissionAllowEnqueue = allowEnqueue;

        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        AtomicReference<CompletableFuture<String>> submitted = new AtomicReference<>();
        Thread submitter = new Thread(() -> submitted.set(ChunkStatusUtils.runChunkGenWithLock(
                new ChunkPos(8, 8),
                0,
                () -> 0,
                named,
                () -> CompletableFuture.completedFuture("worldgen")
        )), "shutdown-worldgen-race");
        try {
            submitter.start();
            assertTrue(passedStopCheck.await(5, TimeUnit.SECONDS), "worldgen admission never reached addPendingLock");
            assertEquals(1, scheduler.getPipelineCount());
            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
            allowEnqueue.countDown();
            submitter.join(5000L);
            assertFalse(submitter.isAlive());
            CompletableFuture<String> future = submitted.get();
            assertTrue(future != null && future.isDone(), "worldgen admission racing shutdown left an incomplete future");
            assertTrue(future.isCompletedExceptionally());
            assertEquals(0, scheduler.getPipelineCount());
            assertEquals(0, scheduler.getPendingLockCount());
        } finally {
            allowEnqueue.countDown();
            submitter.join(1000L);
        }
    }

    @Test
    void synchronousActionFailureCompletesAndReleases() {
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(4, 4)),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> {
                    throw new IllegalStateException("supplier failed");
                }
        );
        assertTrue(lock.tryAcquire());
        lock.doAction(() -> {});
        assertTrue(lock.getFuture().isCompletedExceptionally());
        SchedulingAsyncCombinedLock<String> second = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(4, 4)),
                () -> 0,
                SchedulerThread.INSTANCE,
                () -> CompletableFuture.completedFuture("recovered")
        );
        assertTrue(second.tryAcquire());
        second.doAction(() -> {});
        assertEquals("recovered", second.getFuture().join());
    }

    @Test
    void admittedLockCompletesNormallyThenShutdownIsIdle() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        SchedulingAsyncCombinedLock<String> lock = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(5, 5)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("ok")
        );
        assertTrue(scheduler.addPendingLock(lock));
        assertEquals("ok", lock.getFuture().get(5, TimeUnit.SECONDS));
        scheduler.shutdown();
        scheduler.shutdown();
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(lock.getFuture().isDone());
        assertEquals("ok", lock.getFuture().join());
        assertThrows(IllegalStateException.class, () -> scheduler.execute(() -> {}));
        assertFalse(scheduler.addPendingLock(new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(6, 6)),
                () -> 0,
                scheduler,
                () -> CompletableFuture.completedFuture("after")
        )));
    }

    @Test
    void replaceInstanceTerminatesPreviousAndAcceptsNewWork() throws Exception {
        SchedulerThread previous = SchedulerThread.INSTANCE;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        CountDownLatch hold = new CountDownLatch(1);
        List<CompletableFuture<String>> previousWork = new CopyOnWriteArrayList<>();
        previousWork.add(ChunkStatusUtils.runChunkGenWithLock(
                new ChunkPos(9, 9),
                0,
                () -> 0,
                named,
                () -> CompletableFuture.supplyAsync(() -> {
                    try {
                        hold.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return "held";
                })
        ));
        SchedulerThread.replaceInstance();
        SchedulerThread replacement = SchedulerThread.INSTANCE;
        assertNotSame(previous, replacement);
        assertFalse(previous.isAlive());
        for (CompletableFuture<String> future : previousWork) {
            assertTrue(future.isDone(), "work on the replaced scheduler was stranded");
        }
        hold.countDown();
        SchedulingAsyncCombinedLock<String> fresh = new SchedulingAsyncCombinedLock<>(
                named,
                Set.of(new ChunkPos(10, 10)),
                () -> 0,
                replacement,
                () -> CompletableFuture.completedFuture("replacement")
        );
        assertTrue(replacement.addPendingLock(fresh));
        assertEquals("replacement", fresh.getFuture().get(5, TimeUnit.SECONDS));
    }
}
