package network.vonix.threadedhorizons.common.chunkio;

import network.vonix.threadedhorizons.common.GlobalExecutors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * Per-position linearizable region store. Each accepted write or delete is an
 * immutable generation with its own completion future. Same-position commits
 * run in submission order; stale generations never evict a newer cache entry.
 */
public class ThreadedHorizonsStorageThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Storage");
    private static final AtomicLong SERIAL = new AtomicLong(0);
    private static final int MAX_AUTO_RETRIES = 5;

    private final RegionBackend backend;
    private final Executor serializeExecutor;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final ConcurrentLinkedQueue<Runnable> mailbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ReadCall> pendingReads = new ConcurrentLinkedQueue<>();
    private final Long2ObjectOpenHashMap<PositionSlot> slots = new Long2ObjectOpenHashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong(0);
    private final AtomicInteger pendingAccepted = new AtomicInteger();
    private final AtomicInteger pendingFlushes = new AtomicInteger();
    private final Object admissionLock = new Object();
    private volatile Throwable durabilityFailure;
    public final StorageIoHooks hooks = new StorageIoHooks();

    public ThreadedHorizonsStorageThread(Path directory, boolean dsync, String name) {
        this(new VanillaRegionBackend(directory, dsync), GlobalExecutors.executor, name);
    }

    public ThreadedHorizonsStorageThread(RegionBackend backend, Executor serializeExecutor, String name) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.serializeExecutor = Objects.requireNonNull(serializeExecutor, "serializeExecutor");
        this.setName(name != null && !name.isBlank()
                ? name
                : "Threaded Horizons Storage #%d".formatted(SERIAL.incrementAndGet()));
        this.setDaemon(true);
        this.setUncaughtExceptionHandler((thread, error) -> LOGGER.error("Thread {} died", thread, error));
        this.start();
    }

    @Override
    public void run() {
        try {
            while (true) {
                boolean worked = drainMailbox();
                worked = drainReads() || worked;
                worked = pumpCommits() || worked;
                if (!worked) {
                    if (this.closing.get() && isIdleForClose()) {
                        finishClose();
                        break;
                    }
                    LockSupport.parkNanos("Waiting for storage work", 10_000_000L);
                }
            }
        } catch (Throwable throwable) {
            recordFailure(throwable);
            if (!this.closeFuture.isDone()) {
                this.closeFuture.completeExceptionally(throwable);
            }
        }
        LOGGER.info("Storage thread {} stopped", this.getName());
    }

    public CompletableFuture<CompoundTag> getChunkData(long pos, @Nullable StreamTagVisitor scanner) {
        CompletableFuture<CompoundTag> future = new CompletableFuture<>();
        ReadCall read = new ReadCall(pos, future, scanner);
        synchronized (this.admissionLock) {
            if (admissionClosed()) {
                future.completeExceptionally(new StorageClosedException("storage is closed"));
                return future;
            }
            this.pendingAccepted.incrementAndGet();
            this.pendingReads.add(read);
        }
        LockSupport.unpark(this);
        return future.thenApply(Function.identity());
    }

    public CompletableFuture<Void> store(long pos, @Nullable CompoundTag nbt) {
        CompoundTag snapshot = nbt == null ? null : nbt.copy();
        PositionedRequest request;
        synchronized (this.admissionLock) {
            if (admissionClosed()) {
                return CompletableFuture.failedFuture(new StorageClosedException("reject after close"));
            }
            long generation = this.nextGeneration.incrementAndGet();
            request = new PositionedRequest(
                    pos,
                    generation,
                    snapshot == null ? PositionedRequest.Kind.DELETE : PositionedRequest.Kind.WRITE,
                    snapshot
            );
            this.pendingAccepted.incrementAndGet();
        }
        executeOnStorageThread(() -> admit(request));
        return request.completion;
    }

    public void setChunkData(long pos, @Nullable CompoundTag nbt) {
        store(pos, nbt);
    }

    public CompletableFuture<Void> flush(boolean sync) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (this.admissionLock) {
            if (admissionClosed()) {
                future.completeExceptionally(new StorageClosedException("reject after close"));
                return future;
            }
            this.pendingFlushes.incrementAndGet();
        }
        executeOnStorageThread(() -> {
            Throwable error = null;
            try {
                drainUntilIdle();
                this.hooks.fire(this.hooks.beforeFlush);
                this.hooks.throwIfPresent(this.hooks.flushFault);
                if (sync) {
                    this.backend.flush();
                }
                error = this.durabilityFailure;
            } catch (Throwable throwable) {
                recordFailure(throwable);
                error = throwable;
            } finally {
                this.pendingFlushes.decrementAndGet();
                if (!future.isDone()) {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(null);
                    }
                }
            }
        });
        return future;
    }

    public CompletableFuture<Void> close() {
        synchronized (this.admissionLock) {
            this.closing.set(true);
        }
        LockSupport.unpark(this);
        return this.closeFuture.thenApply(Function.identity());
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        this.join(TimeUnit.NANOSECONDS.toMillis(nanos), (int) (nanos % 1_000_000L));
        return !this.isAlive();
    }

    private void admit(PositionedRequest request) {
        PositionSlot slot = this.slots.computeIfAbsent(request.pos, unused -> new PositionSlot());
        slot.newestGeneration = request.generation;
        slot.newest = request;
        if (request.kind == PositionedRequest.Kind.DELETE) {
            slot.cached = CachedValue.tombstone(request.generation);
        } else {
            slot.cached = CachedValue.present(request.generation, request.snapshot);
        }
        slot.commitQueue.addLast(request);
        if (request.kind == PositionedRequest.Kind.WRITE) {
            startSerialize(request);
        } else {
            request.ready = true;
        }
    }

    private void startSerialize(PositionedRequest request) {
        this.hooks.serializeStarts.incrementAndGet();
        try {
            CompletableFuture.runAsync(() -> serializeRequest(request), this.serializeExecutor);
        } catch (Throwable rejected) {
            LOGGER.warn("Serialization executor rejected generation {} at {}",
                    request.generation, new ChunkPos(request.pos), rejected);
            request.serializeError = rejected;
            onSerializeFinished(request);
        }
    }

    private void serializeRequest(PositionedRequest request) {
        try {
            this.hooks.awaitSerialize(request.generation);
            this.hooks.throwIfPresent(this.hooks.serializeFault);
            if (this.hooks.beforeSerialize != null) {
                this.hooks.beforeSerialize.accept(request.snapshot);
            }
            request.serialized = request.snapshot.copy();
            if (this.hooks.afterSerialize != null) {
                this.hooks.afterSerialize.accept(request.generation);
            }
            request.ready = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            request.serializeError = interrupted;
        } catch (Throwable throwable) {
            request.serializeError = throwable;
        }
        executeOnStorageThread(() -> onSerializeFinished(request));
    }

    private void onSerializeFinished(PositionedRequest request) {
        PositionSlot slot = this.slots.get(request.pos);
        if (request.serializeError != null) {
            if (slot != null) {
                slot.commitQueue.remove(request);
            }
            failDependents(slot, request, request.serializeError);
            return;
        }
        request.ready = true;
    }

    private boolean pumpCommits() {
        boolean worked = false;
        for (PositionSlot slot : this.slots.values()) {
            if (tryCommit(slot)) {
                worked = true;
            }
        }
        return worked;
    }

    private boolean tryCommit(PositionSlot slot) {
        if (slot.inFlight != null) {
            return false;
        }
        while (!slot.commitQueue.isEmpty()) {
            PositionedRequest head = slot.commitQueue.peekFirst();
            if (head == null) {
                return false;
            }
            // Wait only on readiness. A serializeError is finished by
            // onSerializeFinished, not treated as a commit. Close/flush may later
            // requeue the same request with ready=true to retry durability.
            if (!head.ready && head.kind == PositionedRequest.Kind.WRITE) {
                return false;
            }
            slot.commitQueue.removeFirst();
            if (head.generation < slot.newestGeneration) {
                // Submission-order skip: a newer generation exists. Do not write
                // this generation. Keep the newest cache. Complete only after the
                // newest generation is durable.
                slot.superseded.add(head);
                continue;
            }
            slot.inFlight = head;
            commitNow(slot, head);
            return true;
        }
        return false;
    }

    private void commitNow(PositionSlot slot, PositionedRequest request) {
        ChunkPos chunkPos = new ChunkPos(request.pos);
        try {
            if (request.kind == PositionedRequest.Kind.DELETE) {
                this.hooks.fire(this.hooks.beforeClear);
                this.hooks.throwClearFault();
                this.backend.clear(chunkPos);
                this.hooks.durableClears.incrementAndGet();
            } else {
                this.hooks.fire(this.hooks.beforeWrite);
                this.hooks.throwWriteFault();
                CompoundTag payload = request.serialized != null ? request.serialized : request.snapshot;
                this.backend.write(chunkPos, payload);
                this.hooks.durableWrites.incrementAndGet();
            }
            completeSuccess(slot, request);
        } catch (Throwable throwable) {
            handleCommitFailure(slot, request, throwable);
        }
    }

    private void completeSuccess(PositionSlot slot, PositionedRequest request) {
        this.durabilityFailure = null;
        slot.lastDurableGeneration = request.generation;
        if (slot.cached != null && slot.cached.generation == request.generation) {
            // newest durable generation stays readable
            slot.cached = slot.cached;
        }
        finishSuccess(request);
        for (PositionedRequest older : slot.superseded) {
            if (older.generation <= request.generation) {
                finishSuccess(older);
            }
        }
        slot.superseded.removeIf(older -> older.completion.isDone());
        slot.inFlight = null;
    }

    private void handleCommitFailure(PositionSlot slot, PositionedRequest request, Throwable throwable) {
        StorageFailureClass classification = StorageFailureException.classify(throwable);
        if (classification == StorageFailureClass.RETRYABLE && request.attempts.incrementAndGet() <= MAX_AUTO_RETRIES) {
            LOGGER.warn("Retrying storage generation {} at {} ({}/{})",
                    request.generation, new ChunkPos(request.pos), request.attempts.get(), MAX_AUTO_RETRIES, throwable);
            slot.commitQueue.addFirst(request);
            slot.inFlight = null;
            return;
        }
        recordFailure(throwable);
        failDependents(slot, request, throwable);
        slot.inFlight = null;
    }

    private boolean drainReads() {
        boolean worked = false;
        ReadCall read;
        while ((read = this.pendingReads.poll()) != null) {
            worked = true;
            fulfillRead(read);
        }
        return worked;
    }

    private void fulfillRead(ReadCall read) {
        try {
            this.hooks.fire(this.hooks.beforeRead);
            this.hooks.throwIfPresent(this.hooks.readFault);
            PositionSlot slot = this.slots.get(read.pos);
            if (slot != null && slot.cached != null) {
                if (slot.cached.tombstone) {
                    completeRead(read, null);
                    return;
                }
                CompoundTag cached = slot.cached.value;
                if (read.scanner != null) {
                    cached.accept(read.scanner);
                    completeRead(read, null);
                } else {
                    completeRead(read, cached.copy());
                }
                return;
            }
            ChunkPos chunkPos = new ChunkPos(read.pos);
            if (read.scanner != null) {
                this.backend.scan(chunkPos, read.scanner);
                completeRead(read, null);
            } else {
                completeRead(read, this.backend.read(chunkPos));
            }
        } catch (Throwable throwable) {
            completeReadExceptionally(read, throwable);
        }
    }

    private void completeRead(ReadCall read, @Nullable CompoundTag value) {
        releaseRead(read);
        if (!read.future.isDone()) {
            read.future.complete(value);
        }
    }

    private void completeReadExceptionally(ReadCall read, Throwable throwable) {
        releaseRead(read);
        if (!read.future.isDone()) {
            read.future.completeExceptionally(throwable);
        }
    }

    private boolean drainMailbox() {
        boolean worked = false;
        Runnable task;
        while ((task = this.mailbox.poll()) != null) {
            worked = true;
            try {
                task.run();
            } catch (Throwable throwable) {
                LOGGER.error("Storage mailbox task failed", throwable);
                recordFailure(throwable);
            }
        }
        return worked;
    }

    private void drainUntilIdle() {
        this.durabilityFailure = null;
        for (PositionSlot slot : this.slots.values()) {
            if (slot.newest != null) {
                slot.newest.attempts.set(0);
            }
        }
        requeueUndurableNewest();
        int spins = 0;
        while (this.pendingAccepted.get() > 0 || hasInFlight() || hasQueuedCommits()
                || !this.pendingReads.isEmpty() || !this.mailbox.isEmpty()) {
            drainMailbox();
            drainReads();
            pumpCommits();
            if (++spins > 1_000_000) {
                break;
            }
            LockSupport.parkNanos(100_000L);
        }
        if (hasUndurableNewest() && this.durabilityFailure == null) {
            requeueUndurableNewest();
            pumpCommits();
        }
    }

    private boolean hasUndurableNewest() {
        for (PositionSlot slot : this.slots.values()) {
            if (slot.newest != null && slot.lastDurableGeneration < slot.newestGeneration) {
                return true;
            }
        }
        return false;
    }

    private void requeueUndurableNewest() {
        for (PositionSlot slot : this.slots.values()) {
            PositionedRequest newest = slot.newest;
            if (newest == null || slot.lastDurableGeneration >= slot.newestGeneration || slot.inFlight != null) {
                continue;
            }
            if (!slot.commitQueue.contains(newest)) {
                newest.ready = newest.kind == PositionedRequest.Kind.DELETE || newest.serialized != null || newest.snapshot != null;
                slot.commitQueue.addLast(newest);
            }
        }
    }

    private boolean hasInFlight() {
        for (PositionSlot slot : this.slots.values()) {
            if (slot.inFlight != null) {
                return true;
            }
        }
        return false;
    }

    public int getPendingAccepted() {
        return this.pendingAccepted.get();
    }

    public int getMailboxDepth() {
        return this.mailbox.size();
    }

    public int getPendingReadDepth() {
        return this.pendingReads.size();
    }

    private boolean hasQueuedCommits() {
        for (PositionSlot slot : this.slots.values()) {
            if (!slot.commitQueue.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void finishClose() {
        try {
            drainUntilIdle();
            if (!isIdleForClose() || hasQueuedCommits()) {
                terminateOutstanding(this.durabilityFailure != null
                        ? this.durabilityFailure
                        : new StorageClosedException("close with outstanding storage work"));
            }
            this.hooks.fire(this.hooks.beforeClose);
            this.hooks.throwIfPresent(this.hooks.closeFault);
            this.backend.close();
            this.closed.set(true);
            if (hasUndurableNewest() && this.durabilityFailure == null) {
                this.durabilityFailure = new StorageFailureException(
                        StorageFailureClass.PERMANENT,
                        "close with undurable generation remaining",
                        null);
            }
            if (this.durabilityFailure != null) {
                this.closeFuture.completeExceptionally(this.durabilityFailure);
            } else {
                this.closeFuture.complete(null);
            }
        } catch (Throwable throwable) {
            this.closed.set(true);
            recordFailure(throwable);
            terminateOutstanding(throwable);
            this.closeFuture.completeExceptionally(throwable);
        }
    }

    private boolean admissionClosed() {
        return this.closing.get() || this.closed.get();
    }

    private boolean isIdleForClose() {
        return this.pendingAccepted.get() == 0
                && this.pendingFlushes.get() == 0
                && !hasInFlight()
                && this.pendingReads.isEmpty()
                && this.mailbox.isEmpty();
    }

    private void executeOnStorageThread(Runnable task) {
        if (Thread.currentThread() == this) {
            task.run();
        } else {
            this.mailbox.add(task);
            LockSupport.unpark(this);
        }
    }

    private void releasePending(PositionedRequest request) {
        if (request.pendingCounted) {
            request.pendingCounted = false;
            this.pendingAccepted.decrementAndGet();
        }
    }

    private void releaseRead(ReadCall read) {
        if (!read.released) {
            read.released = true;
            this.pendingAccepted.decrementAndGet();
        }
    }

    private void failDependents(PositionSlot slot, PositionedRequest failed, Throwable throwable) {
        failRequest(failed, throwable);
        if (slot == null || failed.generation < slot.newestGeneration) {
            return;
        }
        slot.commitQueue.removeIf(other -> {
            if (other != failed && other.generation <= failed.generation) {
                failRequest(other, throwable);
                return true;
            }
            return false;
        });
        for (PositionedRequest older : slot.superseded) {
            if (older.generation <= failed.generation) {
                failRequest(older, throwable);
            }
        }
        slot.superseded.removeIf(older -> older.completion.isDone());
    }

    private void terminateOutstanding(Throwable throwable) {
        ReadCall read;
        while ((read = this.pendingReads.poll()) != null) {
            releaseRead(read);
            if (!read.future.isDone()) {
                read.future.completeExceptionally(unwrap(throwable));
            }
        }
        for (PositionSlot slot : this.slots.values()) {
            if (slot.inFlight != null) {
                failDependents(slot, slot.inFlight, throwable);
                slot.inFlight = null;
            }
            for (PositionedRequest queued : List.copyOf(slot.commitQueue)) {
                failDependents(slot, queued, throwable);
            }
            slot.commitQueue.clear();
            for (PositionedRequest older : List.copyOf(slot.superseded)) {
                failRequest(older, throwable);
            }
            slot.superseded.clear();
            if (slot.newest != null) {
                failRequest(slot.newest, throwable);
            }
        }
    }

    private void finishSuccess(PositionedRequest request) {
        releasePending(request);
        if (!request.completion.isDone()) {
            request.completion.complete(null);
        }
    }

    private void failRequest(PositionedRequest request, Throwable throwable) {
        releasePending(request);
        if (!request.completion.isDone()) {
            request.completion.completeExceptionally(unwrap(throwable));
        }
    }

    private void recordFailure(Throwable throwable) {
        if (this.durabilityFailure == null) {
            this.durabilityFailure = unwrap(throwable);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current && current instanceof java.util.concurrent.CompletionException) {
            current = current.getCause();
        }
        return current;
    }

    private static final class PositionSlot {
        long newestGeneration;
        long lastDurableGeneration;
        PositionedRequest newest;
        PositionedRequest inFlight;
        CachedValue cached;
        final ArrayDeque<PositionedRequest> commitQueue = new ArrayDeque<>();
        final List<PositionedRequest> superseded = new ArrayList<>();
    }

    private static final class CachedValue {
        final boolean tombstone;
        final long generation;
        final CompoundTag value;

        private CachedValue(boolean tombstone, long generation, CompoundTag value) {
            this.tombstone = tombstone;
            this.generation = generation;
            this.value = value;
        }

        static CachedValue tombstone(long generation) {
            return new CachedValue(true, generation, null);
        }

        static CachedValue present(long generation, CompoundTag value) {
            return new CachedValue(false, generation, value);
        }
    }

    private static final class PositionedRequest {
        enum Kind {WRITE, DELETE}

        final long pos;
        final long generation;
        final Kind kind;
        final CompoundTag snapshot;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final AtomicInteger attempts = new AtomicInteger();
        volatile boolean ready;
        volatile boolean pendingCounted = true;
        volatile CompoundTag serialized;
        volatile Throwable serializeError;

        PositionedRequest(long pos, long generation, Kind kind, CompoundTag snapshot) {
            this.pos = pos;
            this.generation = generation;
            this.kind = kind;
            this.snapshot = snapshot;
        }
    }

    private static final class ReadCall {
        final long pos;
        final CompletableFuture<CompoundTag> future;
        @Nullable
        final StreamTagVisitor scanner;
        boolean released;

        ReadCall(long pos, CompletableFuture<CompoundTag> future, @Nullable StreamTagVisitor scanner) {
            this.pos = pos;
            this.future = future;
            this.scanner = scanner;
        }
    }
}
