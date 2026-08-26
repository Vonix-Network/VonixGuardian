package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(180)
class StorageStateMachineTest {

    private ThreadedHorizonsStorageThread thread;
    private InMemoryRegionBackend backend;
    private ExecutorService serializePool;

    @AfterEach
    void tearDown() {
        if (this.thread != null) {
            this.thread.close();
            try {
                this.thread.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (this.serializePool != null) {
            this.serializePool.shutdownNow();
        }
    }

    private ThreadedHorizonsStorageThread open() {
        this.backend = new InMemoryRegionBackend();
        this.serializePool = Executors.newFixedThreadPool(4);
        this.thread = new ThreadedHorizonsStorageThread(this.backend, this.serializePool, "storage-test");
        return this.thread;
    }

    private static CompoundTag tag(int value) {
        CompoundTag compound = new CompoundTag();
        compound.putInt("v", value);
        return compound;
    }

    @Test
    void lastSubmittedWinsForEverySerializePermutation() throws Exception {
        int[][] orders = {
                {1, 2, 3},
                {1, 3, 2},
                {2, 1, 3},
                {2, 3, 1},
                {3, 1, 2},
                {3, 2, 1}
        };
        for (int[] order : orders) {
            tearDown();
            ThreadedHorizonsStorageThread storage = open();
            long pos = new ChunkPos(4, 5).toLong();
            CountDownLatch entered = new CountDownLatch(4);
            CountDownLatch gate1 = new CountDownLatch(1);
            CountDownLatch gate2 = new CountDownLatch(1);
            CountDownLatch gate3 = new CountDownLatch(1);
            storage.hooks.beforeSerialize = snapshot -> {
                entered.countDown();
                int value = snapshot == null ? 0 : snapshot.getInt("v");
                if (value == 1) {
                    awaitLatch(gate1);
                } else if (value == 2) {
                    awaitLatch(gate2);
                } else if (value == 3) {
                    awaitLatch(gate3);
                }
            };
            CompletableFuture<Void> w1 = storage.store(pos, tag(1));
            CompletableFuture<Void> w2 = storage.store(pos, tag(2));
            CompletableFuture<Void> w3 = storage.store(pos, tag(3));
            CompoundTag reused = tag(99);
            CompletableFuture<Void> w4 = storage.store(pos, reused);
            reused.putInt("v", -1);
            awaitEntered(entered, "beforeSerialize permutation");
            try {
                for (int generation : order) {
                    if (generation == 1) {
                        gate1.countDown();
                    } else if (generation == 2) {
                        gate2.countDown();
                    } else if (generation == 3) {
                        gate3.countDown();
                    }
                }
                CompletableFuture.allOf(w1, w2, w3, w4).get(10, TimeUnit.SECONDS);
            } finally {
                gate1.countDown();
                gate2.countDown();
                gate3.countDown();
            }
            storage.flush(true).get(10, TimeUnit.SECONDS);
            CompoundTag read = storage.getChunkData(pos, null).get(5, TimeUnit.SECONDS);
            assertEquals(99, read.getInt("v"), "permutation " + java.util.Arrays.toString(order));
            assertEquals(99, this.backend.diskGet(pos).getInt("v"));
        }
    }

    @Test
    void storeFutureIsExactAndFailsOnWrite() {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(1, 1).toLong();
        storage.hooks.writeFault = new RuntimeException("disk full");
        CompletableFuture<Void> future = storage.store(pos, tag(7));
        CompletionException thrown = assertThrows(CompletionException.class, () -> future.join());
        assertNotNull(thrown.getCause());
        assertEquals("disk full", thrown.getCause().getMessage());
        assertEquals(7, storage.getChunkData(pos, null).join().getInt("v"));
        storage.hooks.writeFault = null;
        storage.flush(true).join();
        assertEquals(7, this.backend.diskGet(pos).getInt("v"));
    }

    @Test
    void deleteIsVisibleAsAbsenceWhileClearIsBlocked() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(2, 2).toLong();
        storage.store(pos, tag(3)).join();
        storage.flush(true).join();
        storage.hooks.clearFault = new RuntimeException("clear blocked");
        CompletableFuture<Void> delete = storage.store(pos, null);
        assertThrows(CompletionException.class, delete::join);
        assertNull(storage.getChunkData(pos, null).join());
        assertNotNull(this.backend.diskGet(pos));
        storage.hooks.clearFault = null;
        storage.flush(true).join();
        assertNull(this.backend.diskGet(pos));
    }

    @Test
    void flushAndClosePropagateForceFailure() {
        ThreadedHorizonsStorageThread storage = open();
        storage.hooks.flushFault = new RuntimeException("force failed");
        CompletionException flush = assertThrows(CompletionException.class, () -> storage.flush(true).join());
        assertEquals("force failed", flush.getCause().getMessage());
        storage.hooks.flushFault = null;
        storage.hooks.closeFault = new RuntimeException("close failed");
        CompletionException close = assertThrows(CompletionException.class, () -> storage.close().join());
        assertEquals("close failed", close.getCause().getMessage());
        this.thread = null;
    }

    @Test
    void rejectAfterClose() {
        ThreadedHorizonsStorageThread storage = open();
        storage.close().join();
        CompletableFuture<Void> rejected = storage.store(new ChunkPos(0, 0).toLong(), tag(1));
        CompletionException thrown = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(StorageClosedException.class, thrown.getCause());
        CompletionException read = assertThrows(CompletionException.class,
                () -> storage.getChunkData(new ChunkPos(0, 0).toLong(), null).join());
        assertInstanceOf(StorageClosedException.class, read.getCause());
        CompletionException flush = assertThrows(CompletionException.class, () -> storage.flush(true).join());
        assertInstanceOf(StorageClosedException.class, flush.getCause());
        this.thread = null;
    }

    @Test
    void linearizableHistoryOnConcurrentOps() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        ExecutorService clients = Executors.newFixedThreadPool(8);
        AtomicInteger next = new AtomicInteger();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        Object[] locks = new Object[16];
        int[] lastValue = new int[16];
        boolean[] deleted = new boolean[16];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
            lastValue[i] = Integer.MIN_VALUE;
            deleted[i] = true;
        }
        for (int i = 0; i < 100_000; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                int ticket = next.getAndIncrement();
                int index = Math.floorMod(ticket, 16);
                long pos = new ChunkPos(index, 0).toLong();
                int kind = Math.floorMod(ticket, 5);
                synchronized (locks[index]) {
                    if (kind == 0) {
                        storage.store(pos, null).join();
                        deleted[index] = true;
                    } else if (kind == 1) {
                        CompoundTag seen = storage.getChunkData(pos, null).join();
                        if (deleted[index]) {
                            assertNull(seen);
                        } else {
                            assertNotNull(seen);
                            assertEquals(lastValue[index], seen.getInt("v"));
                        }
                    } else {
                        storage.store(pos, tag(ticket)).join();
                        lastValue[index] = ticket;
                        deleted[index] = false;
                    }
                }
            }, clients));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
        storage.flush(true).join();
        for (int index = 0; index < 16; index++) {
            long pos = new ChunkPos(index, 0).toLong();
            CompoundTag disk = this.backend.diskGet(pos);
            if (deleted[index]) {
                assertNull(disk, "position " + index);
            } else {
                assertNotNull(disk, "position " + index);
                assertEquals(lastValue[index], disk.getInt("v"), "position " + index);
            }
        }
        clients.shutdownNow();
        assertTrue(storage.hooks.durableWrites.get() + storage.hooks.durableClears.get() >= 0);
    }

    @Test
    void acceptedFutureBecomingTerminalHasAlreadyReleasedAdmission() throws Exception {
        assertNewestWriteFailureReleasesAdmissionBeforeTerminal();
        tearDown();
        assertNewestSerializeFailureReleasesAdmissionBeforeTerminal();
        tearDown();
        assertSuccessfulSupersedeReleasesAdmissionBeforeTerminal();
        tearDown();
        assertAdmittedReadReleasesAdmissionBeforeTerminal();
    }

    private void assertNewestWriteFailureReleasesAdmissionBeforeTerminal() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(30, 31).toLong();
        BeforeSerializeBarrier barrier = BeforeSerializeBarrier.hold(2);
        storage.hooks.beforeSerialize = barrier::accept;
        storage.hooks.writeFault = new RuntimeException(
                new StorageFailureException(StorageFailureClass.PERMANENT, "newest write exhausted", null));
        CompletableFuture<Void> older = storage.store(pos, tag(1));
        CompletableFuture<Void> newest = storage.store(pos, tag(2));
        AdmissionWatch watch = watchAdmissionRelease(storage, 2, older, newest);
        barrier.awaitEnteredAndRelease();
        assertThrows(CompletionException.class, older::join);
        assertThrows(CompletionException.class, newest::join);
        watch.assertReleasedBeforeEachTerminal();
        assertEquals(0, storage.getPendingAccepted());
        assertThrows(CompletionException.class, () -> storage.close().join());
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    private void assertNewestSerializeFailureReleasesAdmissionBeforeTerminal() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(32, 33).toLong();
        BeforeSerializeBarrier barrier = BeforeSerializeBarrier.holdThenFailValue(2, 2);
        storage.hooks.beforeSerialize = barrier::accept;
        CompletableFuture<Void> older = storage.store(pos, tag(1));
        CompletableFuture<Void> newest = storage.store(pos, tag(2));
        AdmissionWatch watch = watchAdmissionRelease(storage, 2, older, newest);
        barrier.awaitEnteredAndRelease();
        assertThrows(CompletionException.class, older::join);
        assertThrows(CompletionException.class, newest::join);
        watch.assertReleasedBeforeEachTerminal();
        assertEquals(0, storage.getPendingAccepted());
        storage.close().get(5, TimeUnit.SECONDS);
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    private void assertSuccessfulSupersedeReleasesAdmissionBeforeTerminal() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(34, 35).toLong();
        BeforeSerializeBarrier barrier = BeforeSerializeBarrier.hold(2);
        storage.hooks.beforeSerialize = barrier::accept;
        CompletableFuture<Void> older = storage.store(pos, tag(1));
        CompletableFuture<Void> newest = storage.store(pos, tag(2));
        AdmissionWatch watch = watchAdmissionRelease(storage, 2, older, newest);
        barrier.awaitEnteredAndRelease();
        older.get(10, TimeUnit.SECONDS);
        newest.get(10, TimeUnit.SECONDS);
        watch.assertReleasedBeforeEachTerminal();
        assertEquals(0, storage.getPendingAccepted());
        storage.flush(true).get(10, TimeUnit.SECONDS);
        assertEquals(2, this.backend.diskGet(pos).getInt("v"));
        storage.close().get(5, TimeUnit.SECONDS);
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    private void assertAdmittedReadReleasesAdmissionBeforeTerminal() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(36, 37).toLong();
        storage.store(pos, tag(9)).join();
        assertEquals(0, storage.getPendingAccepted());
        CountDownLatch enteredRead = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        storage.hooks.beforeRead = () -> {
            enteredRead.countDown();
            awaitLatch(releaseRead);
        };
        try {
            CompletableFuture<CompoundTag> read = storage.getChunkData(pos, null);
            AdmissionWatch watch = watchAdmissionRelease(storage, 1, read);
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS));
            releaseRead.countDown();
            assertEquals(9, read.get(5, TimeUnit.SECONDS).getInt("v"));
            watch.assertReleasedBeforeEachTerminal();
            assertEquals(0, storage.getPendingAccepted());
        } finally {
            releaseRead.countDown();
        }
        storage.close().get(5, TimeUnit.SECONDS);
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    private static AdmissionWatch watchAdmissionRelease(
            ThreadedHorizonsStorageThread storage,
            int accepted,
            CompletableFuture<?>... futures) {
        return new AdmissionWatch(storage, accepted, futures);
    }

    private static final class AdmissionWatch {
        private final AtomicInteger remaining;
        private final AtomicReference<AssertionError> violation = new AtomicReference<>();

        private AdmissionWatch(
                ThreadedHorizonsStorageThread storage,
                int accepted,
                CompletableFuture<?>... futures) {
            this.remaining = new AtomicInteger(accepted);
            for (CompletableFuture<?> future : futures) {
                future.whenComplete((ignored, error) -> {
                    int pending = storage.getPendingAccepted();
                    int leftover = this.remaining.decrementAndGet();
                    if (pending > leftover) {
                        this.violation.compareAndSet(null, new AssertionError(
                                "accepted future became terminal with pendingAccepted="
                                        + pending + " leftoverUnobserved=" + leftover
                                        + "; admission must already be released"));
                    }
                });
            }
        }

        private void assertReleasedBeforeEachTerminal() {
            AssertionError error = this.violation.get();
            if (error != null) {
                throw error;
            }
            assertEquals(0, this.remaining.get(), "every watched future must have become terminal");
        }
    }

    @Test
    void supersededNewestTerminalFailureResolvesDependentsAndClose() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(8, 9).toLong();
        BeforeSerializeBarrier barrier = BeforeSerializeBarrier.hold(2);
        storage.hooks.beforeSerialize = barrier::accept;
        storage.hooks.writeFault = new RuntimeException(
                new StorageFailureException(StorageFailureClass.PERMANENT, "newest generation exhausted", null));
        CompletableFuture<Void> older = storage.store(pos, tag(1));
        CompletableFuture<Void> newest = storage.store(pos, tag(2));
        barrier.awaitEnteredAndRelease();
        CompletionException olderThrown = assertThrows(CompletionException.class, older::join);
        CompletionException newestThrown = assertThrows(CompletionException.class, newest::join);
        assertTrue(older.isDone());
        assertTrue(newest.isDone());
        assertTrue(older.isCompletedExceptionally());
        assertTrue(newest.isCompletedExceptionally());
        assertNotNull(rootCause(olderThrown));
        assertNotNull(rootCause(newestThrown));
        assertEquals(0, storage.getPendingAccepted());
        CompletionException closed = assertThrows(CompletionException.class, () -> storage.close().join());
        assertNotNull(rootCause(closed));
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, storage.getPendingAccepted());
        this.thread = null;
    }

    @Test
    void newestSerializeFailureFailsSupersededAndClose() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(10, 11).toLong();
        BeforeSerializeBarrier barrier = BeforeSerializeBarrier.holdThenFailValue(2, 2);
        storage.hooks.beforeSerialize = barrier::accept;
        CompletableFuture<Void> older = storage.store(pos, tag(1));
        CompletableFuture<Void> newest = storage.store(pos, tag(2));
        barrier.awaitEnteredAndRelease();
        assertThrows(CompletionException.class, older::join);
        assertThrows(CompletionException.class, newest::join);
        assertTrue(older.isCompletedExceptionally());
        assertTrue(newest.isCompletedExceptionally());
        assertEquals(0, storage.getPendingAccepted());
        storage.close().get(5, TimeUnit.SECONDS);
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    @Test
    void persistentDiskFailureDuringCloseTerminatesEveryFuture() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(12, 13).toLong();
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch failWrite = new CountDownLatch(1);
        storage.hooks.beforeWrite = () -> {
            enteredWrite.countDown();
            awaitLatch(failWrite);
            throw new RuntimeException(
                    new StorageFailureException(StorageFailureClass.PERMANENT, "disk dead during close", null));
        };
        CompletableFuture<Void> stored = storage.store(pos, tag(4));
        assertTrue(enteredWrite.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> closed = storage.close();
        failWrite.countDown();
        assertThrows(CompletionException.class, stored::join);
        assertThrows(CompletionException.class, closed::join);
        assertTrue(stored.isDone());
        assertTrue(closed.isDone());
        assertEquals(0, storage.getPendingAccepted());
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    @Test
    void admittedReadCompletesAndCloseWaits() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(14, 15).toLong();
        storage.store(pos, tag(5)).join();
        CountDownLatch enteredRead = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        storage.hooks.beforeRead = () -> {
            enteredRead.countDown();
            awaitLatch(releaseRead);
        };
        try {
            CompletableFuture<CompoundTag> admitted = storage.getChunkData(pos, null);
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> closed = storage.close();
            assertFalse(closed.isDone());
            releaseRead.countDown();
            assertEquals(5, admitted.get(5, TimeUnit.SECONDS).getInt("v"));
            closed.get(5, TimeUnit.SECONDS);
            assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseRead.countDown();
        }
        this.thread = null;
    }

    @Test
    void readDuringCloseIsRejectedFailClosed() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        long pos = new ChunkPos(14, 16).toLong();
        storage.store(pos, tag(6)).join();
        AtomicReference<CompletableFuture<CompoundTag>> raced = new AtomicReference<>();
        storage.hooks.beforeClose = () -> raced.set(storage.getChunkData(pos, null));
        storage.close().join();
        CompletableFuture<CompoundTag> rejected = raced.get();
        assertNotNull(rejected);
        assertTrue(rejected.isDone());
        CompletionException thrown = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(StorageClosedException.class, rootCause(thrown));
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    @Test
    void admittedFlushCompletesAndCloseWaits() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        storage.store(new ChunkPos(16, 17).toLong(), tag(7)).join();
        CountDownLatch enteredFlush = new CountDownLatch(1);
        CountDownLatch releaseFlush = new CountDownLatch(1);
        storage.hooks.beforeFlush = () -> {
            enteredFlush.countDown();
            awaitLatch(releaseFlush);
        };
        try {
            CompletableFuture<Void> admitted = storage.flush(true);
            assertTrue(enteredFlush.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> closed = storage.close();
            assertFalse(closed.isDone());
            assertFalse(admitted.isDone());
            releaseFlush.countDown();
            admitted.get(5, TimeUnit.SECONDS);
            closed.get(5, TimeUnit.SECONDS);
            assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseFlush.countDown();
        }
        this.thread = null;
    }

    @Test
    void rejectedSerializeExecutorTerminatesStoreAndClose() throws Exception {
        this.backend = new InMemoryRegionBackend();
        this.serializePool = null;
        Executor rejecting = command -> {
            throw new RejectedExecutionException("serialize executor rejected");
        };
        this.thread = new ThreadedHorizonsStorageThread(this.backend, rejecting, "storage-reject");
        long pos = new ChunkPos(20, 21).toLong();
        CompletableFuture<Void> stored = this.thread.store(pos, tag(1));
        CompletionException thrown = assertThrows(CompletionException.class, stored::join);
        assertInstanceOf(RejectedExecutionException.class, rootCause(thrown));
        assertEquals("serialize executor rejected", rootCause(thrown).getMessage());
        assertTrue(stored.isDone());
        assertTrue(stored.isCompletedExceptionally());
        assertEquals(0, this.thread.getPendingAccepted());
        this.thread.close().get(5, TimeUnit.SECONDS);
        assertTrue(this.thread.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, this.thread.getPendingAccepted());
        this.thread = null;
    }

    @Test
    void rejectedNewestSerializeFailsSupersededAndClose() throws Exception {
        this.backend = new InMemoryRegionBackend();
        this.serializePool = Executors.newSingleThreadExecutor();
        AtomicBoolean rejectNewest = new AtomicBoolean(false);
        Executor gated = command -> {
            if (rejectNewest.get()) {
                throw new RejectedExecutionException("serialize executor rejected newest");
            }
            this.serializePool.execute(command);
        };
        this.thread = new ThreadedHorizonsStorageThread(this.backend, gated, "storage-reject-newest");
        long pos = new ChunkPos(22, 23).toLong();
        BeforeSerializeBarrier olderBarrier = BeforeSerializeBarrier.hold(1);
        this.thread.hooks.beforeSerialize = olderBarrier::accept;
        CompletableFuture<Void> older = this.thread.store(pos, tag(1));
        try {
            olderBarrier.awaitEntered();
            rejectNewest.set(true);
            CompletableFuture<Void> newest = this.thread.store(pos, tag(2));
            olderBarrier.release();
            CompletionException olderThrown = assertThrows(CompletionException.class, older::join);
            CompletionException newestThrown = assertThrows(CompletionException.class, newest::join);
            assertTrue(older.isCompletedExceptionally());
            assertTrue(newest.isCompletedExceptionally());
            assertNotNull(rootCause(olderThrown));
            assertInstanceOf(RejectedExecutionException.class, rootCause(newestThrown));
            assertEquals(0, this.thread.getPendingAccepted());
            this.thread.close().get(5, TimeUnit.SECONDS);
            assertTrue(this.thread.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, this.thread.getPendingAccepted());
            this.thread = null;
        } finally {
            olderBarrier.release();
        }
    }

    @Test
    void flushDuringCloseIsRejectedFailClosed() throws Exception {
        ThreadedHorizonsStorageThread storage = open();
        storage.store(new ChunkPos(18, 19).toLong(), tag(8)).join();
        AtomicReference<CompletableFuture<Void>> raced = new AtomicReference<>();
        storage.hooks.beforeClose = () -> raced.set(storage.flush(true));
        storage.close().join();
        CompletableFuture<Void> rejected = raced.get();
        assertNotNull(rejected);
        assertTrue(rejected.isDone());
        CompletionException thrown = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(StorageClosedException.class, rootCause(thrown));
        assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
        this.thread = null;
    }

    private static final class BeforeSerializeBarrier {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final Integer failValue;

        private BeforeSerializeBarrier(int parties, Integer failValue) {
            this.entered = new CountDownLatch(parties);
            this.release = new CountDownLatch(1);
            this.failValue = failValue;
        }

        private static BeforeSerializeBarrier hold(int parties) {
            return new BeforeSerializeBarrier(parties, null);
        }

        private static BeforeSerializeBarrier holdThenFailValue(int parties, int value) {
            return new BeforeSerializeBarrier(parties, value);
        }

        private void accept(CompoundTag snapshot) {
            this.entered.countDown();
            awaitLatch(this.release);
            if (this.failValue != null && snapshot != null && snapshot.getInt("v") == this.failValue) {
                throw new RuntimeException("serialize newest failed");
            }
        }

        private void awaitEntered() {
            StorageStateMachineTest.awaitEntered(this.entered, "beforeSerialize");
        }

        private void release() {
            this.release.countDown();
        }

        private void awaitEnteredAndRelease() {
            try {
                awaitEntered();
            } finally {
                release();
            }
        }
    }

    private static void awaitEntered(CountDownLatch entered, String label) {
        try {
            if (!entered.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(label + " remaining=" + entered.getCount());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("storage interleave latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
