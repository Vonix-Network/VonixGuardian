package network.vonix.threadedhorizons.common.threading;

import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(45)
class SchedulerAdmissionBoundTest {

    @AfterEach
    void resetScheduler() {
        SchedulerThread.replaceInstance();
    }

    @Test
    void burstyChunkAdmissionStaysWithinPipelineLimitAndCompletes() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int limit = scheduler.getPipelineLimit();
        assertEquals(
                SchedulerThread.computePipelineLimit(
                        ThreadedHorizonsConfig.globalExecutorParallelism,
                        Runtime.getRuntime().maxMemory()),
                limit);
        assertTrue(limit >= 8 && limit <= 64, "pipeline limit=" + limit);

        int burst = limit * 4;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peakRunning = new AtomicInteger();
        AtomicInteger peakOccupancy = new AtomicInteger();
        List<CompletableFuture<String>> futures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(burst);
        try {
            for (int index = 0; index < burst; index++) {
                int chunkX = index;
                pool.execute(() -> {
                    CompletableFuture<String> future = ChunkStatusUtils.runChunkGenWithLock(
                            new ChunkPos(chunkX, 0),
                            0,
                            () -> 0,
                            named,
                            () -> CompletableFuture.supplyAsync(() -> {
                                int now = running.incrementAndGet();
                                peakRunning.accumulateAndGet(now, Math::max);
                                recordOccupancy(scheduler, peakOccupancy);
                                try {
                                    hold.await(20, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    running.decrementAndGet();
                                }
                                return "ok";
                            })
                    );
                    futures.add(future);
                });
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                recordOccupancy(scheduler, peakOccupancy);
                if (futures.size() >= burst) {
                    break;
                }
                Thread.sleep(10L);
            }
            recordOccupancy(scheduler, peakOccupancy);

            assertTrue(
                    peakOccupancy.get() <= limit,
                    "retained occupancy " + peakOccupancy.get() + " exceeded pipeline limit " + limit
                            + " pending=" + scheduler.getPendingLockCount()
                            + " pipeline=" + scheduler.getPipelineCount()
                            + " permits=" + scheduler.getAvailablePermits()
            );

            hold.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "submitters did not finish");
            assertEquals(burst, futures.size());
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            for (CompletableFuture<String> future : futures) {
                assertEquals("ok", future.join());
            }
            assertEquals(0, scheduler.getPipelineCount());
            assertEquals(0, scheduler.getPendingLockCount());
            assertTrue(peakRunning.get() >= 1, "expected concurrent-capable execution");
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void sameChunkEnterIsIdempotentUntilLeave() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        ChunkPos pos = new ChunkPos(99, 99);
        scheduler.enterChunkPipeline(pos);
        scheduler.enterChunkPipeline(pos);
        assertEquals(1, scheduler.getPipelineCount());
        int extra = 0;
        while (scheduler.getPipelineCount() < scheduler.getPipelineLimit()) {
            scheduler.enterChunkPipeline(new ChunkPos(extra++, 100));
        }
        assertEquals(scheduler.getPipelineLimit(), scheduler.getPipelineCount());
        AtomicInteger blocked = new AtomicInteger();
        Thread waiter = new Thread(() -> {
            try {
                blocked.set(1);
                scheduler.enterChunkPipeline(new ChunkPos(200, 200));
                blocked.set(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException ignored) {
                blocked.set(3);
            }
        }, "pipeline-waiter");
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (blocked.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(1, blocked.get(), "new chunk must wait when the pipeline is full");
        scheduler.leaveChunkPipeline(pos);
        waiter.join(2000L);
        assertEquals(2, blocked.get());
        assertEquals(scheduler.getPipelineLimit(), scheduler.getPipelineCount());
        scheduler.leaveChunkPipeline(new ChunkPos(200, 200));
        for (int index = 0; index < extra; index++) {
            scheduler.leaveChunkPipeline(new ChunkPos(index, 100));
        }
        assertEquals(0, scheduler.getPipelineCount());
    }

    @Test
    void shutdownFailsBlockedAdmissionsInsteadOfHangingOrDropping() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int limit = scheduler.getPipelineLimit();
        int burst = limit + 8;
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        CountDownLatch hold = new CountDownLatch(1);
        List<CompletableFuture<String>> futures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(burst);
        try {
            for (int index = 0; index < burst; index++) {
                int chunkX = index;
                pool.execute(() -> futures.add(ChunkStatusUtils.runChunkGenWithLock(
                        new ChunkPos(chunkX, 1),
                        0,
                        () -> 0,
                        named,
                        () -> CompletableFuture.supplyAsync(() -> {
                            try {
                                hold.await(20, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return "held";
                        })
                )));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && futures.size() < limit) {
                Thread.sleep(10L);
            }
            scheduler.shutdown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "shutdown left submitters blocked");
            assertEquals(burst, futures.size());
            for (CompletableFuture<String> future : futures) {
                assertTrue(future.isDone(), "admitted or waiting future was dropped");
            }
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fullPipelineAfterIntermediateStatusStillMakesProgressOnSubsequentAdmission() throws Exception {
        SchedulerThread scheduler = SchedulerThread.INSTANCE;
        int limit = scheduler.getPipelineLimit();
        assertTrue(limit >= 8 && limit <= 64, "pipeline limit=" + limit);
        AsyncNamedLock<ChunkPos> named = AsyncNamedLock.createFair();
        AtomicInteger peakOccupancy = new AtomicInteger();

        // Observed G6 state: unique chunks still occupy the pipeline after an
        // intermediate status completed, and the scheduler has no runnable work.
        for (int index = 0; index < limit; index++) {
            scheduler.enterChunkPipeline(new ChunkPos(index, 7));
        }
        recordOccupancy(scheduler, peakOccupancy);
        assertEquals(limit, scheduler.getPipelineCount());
        assertEquals(0, scheduler.getPendingLockCount());

        ExecutorService mailbox = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "simulated-server-thread");
            thread.setDaemon(true);
            return thread;
        });
        List<CompletableFuture<String>> later = new CopyOnWriteArrayList<>();
        AtomicInteger mailboxTasksFinished = new AtomicInteger();
        try {
            CompletableFuture<Void> requestNewAdmission = CompletableFuture.runAsync(() -> {
                later.add(ChunkStatusUtils.runChunkGenWithLock(
                        new ChunkPos(limit, 7),
                        0,
                        () -> 0,
                        named,
                        () -> CompletableFuture.completedFuture("extra")));
                mailboxTasksFinished.incrementAndGet();
            }, mailbox);
            CompletableFuture<Void> requestSubsequentStatuses = CompletableFuture.runAsync(() -> {
                for (int index = 0; index < limit; index++) {
                    int chunkX = index;
                    later.add(ChunkStatusUtils.runChunkGenWithLock(
                            new ChunkPos(chunkX, 7),
                            0,
                            () -> 0,
                            named,
                            () -> CompletableFuture.completedFuture("full")));
                }
                mailboxTasksFinished.incrementAndGet();
            }, mailbox);

            try {
                requestNewAdmission.get(8, TimeUnit.SECONDS);
                requestSubsequentStatuses.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                fail("server thread stayed parked in pipeline admission after intermediate status; "
                        + "mailboxProgress=" + mailboxTasksFinished.get()
                        + " later=" + later.size()
                        + " pipeline=" + scheduler.getPipelineCount()
                        + " pending=" + scheduler.getPendingLockCount());
            }
            assertEquals(2, mailboxTasksFinished.get(), "mailbox must keep running after a full pipeline");
            assertEquals(limit + 1, later.size());
            recordOccupancy(scheduler, peakOccupancy);
            CompletableFuture.allOf(later.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            int extra = 0;
            int full = 0;
            for (CompletableFuture<String> future : later) {
                assertTrue(future.isDone(), "admitted or waiting future was dropped");
                String value = future.join();
                if ("extra".equals(value)) {
                    extra++;
                } else if ("full".equals(value)) {
                    full++;
                } else {
                    fail("unexpected generation result " + value);
                }
            }
            assertEquals(1, extra, "new admission after a full pipeline was dropped");
            assertEquals(limit, full, "subsequent FULL status was dropped");
            recordOccupancy(scheduler, peakOccupancy);
            assertTrue(
                    peakOccupancy.get() <= limit,
                    "retained occupancy " + peakOccupancy.get() + " exceeded pipeline limit " + limit
                            + " pipeline=" + scheduler.getPipelineCount()
                            + " pending=" + scheduler.getPendingLockCount());
            for (int index = 0; index <= limit; index++) {
                scheduler.leaveChunkPipeline(new ChunkPos(index, 7));
            }
            assertEquals(0, scheduler.getPipelineCount());
            assertEquals(0, scheduler.getPendingLockCount());
            assertEquals(0, scheduler.getPipelineWaiterCount());
        } finally {
            mailbox.shutdownNow();
            mailbox.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void recordOccupancy(SchedulerThread scheduler, AtomicInteger peakOccupancy) {
        int inFlight = Math.max(0, ThreadedHorizonsConfig.globalExecutorParallelism - scheduler.getAvailablePermits());
        int occupancy = Math.max(scheduler.getPipelineCount(), scheduler.getPendingLockCount() + inFlight);
        peakOccupancy.accumulateAndGet(occupancy, Math::max);
    }
}
