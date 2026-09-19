package network.vonix.threadedhorizons.common.threading.scheduler;

import com.google.common.base.Preconditions;
import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class SchedulingAsyncCombinedLock<T> implements Comparable<SchedulingAsyncCombinedLock<T>> {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Combined Lock");

    private final AsyncNamedLock<ChunkPos> lock;
    private final ChunkPos[] names;
    private final IntSupplier priority;
    private final SchedulerThread schedulerThread;
    private final Supplier<CompletableFuture<T>> action;
    private final CompletableFuture<T> future = new CompletableFuture<>();
    private AsyncLock.LockToken acquiredToken;

    public SchedulingAsyncCombinedLock(AsyncNamedLock<ChunkPos> lock, Set<ChunkPos> names, IntSupplier priority, SchedulerThread schedulerThread, Supplier<CompletableFuture<T>> action) {
        this.lock = lock;
        this.names = names.toArray(ChunkPos[]::new);
        this.priority = priority;
        this.schedulerThread = schedulerThread;
        this.action = action;
    }

    public synchronized boolean tryAcquire() {
        final LockEntry[] tryLocks = new LockEntry[names.length];
        boolean allAcquired = true;
        for (int i = 0, namesLength = names.length; i < namesLength; i++) {
            ChunkPos name = names[i];
            final LockEntry entry = new LockEntry(name, this.lock.tryLock(name));
            tryLocks[i] = entry;
            if (entry.lockToken.isEmpty()) {
                allAcquired = false;
                break;
            }
        }
        if (allAcquired) {
            this.acquiredToken = () -> {
                for (LockEntry entry : tryLocks) {
                    //noinspection OptionalGetWithoutIsPresent
                    entry.lockToken.get().releaseLock(); // if it isn't present then something is really wrong
                }
            };
            return true;
        } else {
            boolean triedRelock = false;
            for (LockEntry entry : tryLocks) {
                if (entry == null) continue;
                entry.lockToken.ifPresent(AsyncLock.LockToken::releaseLock);
                if (!triedRelock && entry.lockToken.isEmpty()) {
                    this.lock.acquireLock(entry.name).thenAccept(lockToken -> {
                        lockToken.releaseLock();
                        this.schedulerThread.addPendingLock(this);
                    });
                    triedRelock = true;
                }
            }
            if (!triedRelock) {
                // shouldn't happen at all...
                System.err.println("Some issue occurred while doing locking, retrying");
                return this.tryAcquire();
            }
            return false;
        }
    }

    public void doAction(Runnable postAction) {
        Preconditions.checkNotNull(postAction);
        AsyncLock.LockToken token = this.acquiredToken;
        if (token == null) throw new IllegalStateException();
        CompletableFuture<T> stage;
        try {
            stage = this.action.get();
        } catch (Throwable throwable) {
            releaseOnce(token, postAction);
            this.future.completeExceptionally(throwable);
            return;
        }
        if (stage == null) {
            releaseOnce(token, postAction);
            this.future.completeExceptionally(new NullPointerException("future"));
            return;
        }
        stage.whenComplete((result, throwable) -> {
            releaseOnce(token, postAction);
            if (throwable != null) this.future.completeExceptionally(throwable);
            else this.future.complete(result);
        });
    }

    private void releaseOnce(AsyncLock.LockToken token, Runnable postAction) {
        try {
            token.releaseLock();
        } catch (Throwable t) {
            LOGGER.warn("Failed to release combined lock", t);
        }
        try {
            postAction.run();
        } catch (Throwable t) {
            LOGGER.warn("Failed to release scheduler permit", t);
        }
    }

    public CompletableFuture<T> getFuture() {
        return this.future;
    }

    @Override
    public int compareTo(@NotNull SchedulingAsyncCombinedLock o) {
        return Integer.compare(this.priority.getAsInt(), o.priority.getAsInt());
    }

    private record LockEntry(ChunkPos name,
                             Optional<AsyncLock.LockToken> lockToken) {
    }
}
