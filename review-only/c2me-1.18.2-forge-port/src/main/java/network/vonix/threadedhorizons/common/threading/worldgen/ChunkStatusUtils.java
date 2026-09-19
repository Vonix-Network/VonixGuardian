package network.vonix.threadedhorizons.common.threading.worldgen;

import com.google.common.base.Preconditions;
import com.ibm.asyncutil.locks.AsyncLock;
import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulerThread;
import network.vonix.threadedhorizons.common.threading.scheduler.SchedulingAsyncCombinedLock;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils.ChunkStatusThreadingType.AS_IS;
import static network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils.ChunkStatusThreadingType.PARALLELIZED;
import static network.vonix.threadedhorizons.common.threading.worldgen.ChunkStatusUtils.ChunkStatusThreadingType.SINGLE_THREADED;

public class ChunkStatusUtils {

    public static ChunkStatusThreadingType getThreadingType(final ChunkStatus status) {
        if (status.equals(ChunkStatus.STRUCTURE_STARTS)
                || status.equals(ChunkStatus.STRUCTURE_REFERENCES)
                || status.equals(ChunkStatus.BIOMES)
                || status.equals(ChunkStatus.NOISE)
                || status.equals(ChunkStatus.SURFACE)
                || status.equals(ChunkStatus.CARVERS)
                || status.equals(ChunkStatus.LIQUID_CARVERS)
                || status.equals(ChunkStatus.HEIGHTMAPS)) {
            return PARALLELIZED;
        } else if (status.equals(ChunkStatus.SPAWN)) {
            return SINGLE_THREADED;
        } else if (status.equals(ChunkStatus.FEATURES)) {
            return ThreadedHorizonsConfig.threadedWorldGenConfig.allowThreadedFeatures ? PARALLELIZED : SINGLE_THREADED;
        }
        return AS_IS;
    }

    public static <T> CompletableFuture<T> runChunkGenWithLock(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action) {
        return runChunkGenWithLock(target, radius, priority, chunkLock, action, null);
    }

    public static <T> CompletableFuture<T> runChunkGenWithLock(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action, ChunkStatus status) {
        Preconditions.checkNotNull(priority);
        Preconditions.checkNotNull(target);
        // `status` is the mixin-facing stage. Admission is released when this
        // stage completes so a later status or neighbor can take the slot.
        if (Thread.currentThread() == SchedulerThread.INSTANCE) {
            return CompletableFuture.supplyAsync(
                    () -> runChunkGenWithLock(target, radius, priority, chunkLock, action, status),
                    GlobalExecutors.executor
            ).thenCompose(Function.identity());
        }

        return SchedulerThread.INSTANCE.acquireChunkPipelineAsync(target)
                .thenCompose(ignored -> startAdmittedChunkGen(target, radius, priority, chunkLock, action));
    }

    private static <T> CompletableFuture<T> startAdmittedChunkGen(ChunkPos target, int radius, IntSupplier priority, AsyncNamedLock<ChunkPos> chunkLock, Supplier<CompletableFuture<T>> action) {
        boolean releaseImmediately = false;
        try {
            if (SchedulerThread.INSTANCE.isStopping()) {
                releaseImmediately = true;
                return CompletableFuture.failedFuture(new IllegalStateException("scheduler is stopping"));
            }

            ArrayList<ChunkPos> fetchedLocks = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
            for (int x = target.x - radius; x <= target.x + radius; x++)
                for (int z = target.z - radius; z <= target.z + radius; z++)
                    fetchedLocks.add(new ChunkPos(x, z));

            final SchedulingAsyncCombinedLock<T> lock = new SchedulingAsyncCombinedLock<>(chunkLock, new HashSet<>(fetchedLocks), priority, SchedulerThread.INSTANCE, action);
            if (!SchedulerThread.INSTANCE.addPendingLock(lock)) {
                releaseImmediately = true;
                return lock.getFuture();
            }
            return lock.getFuture().whenComplete((result, throwable) -> SchedulerThread.INSTANCE.leaveChunkPipeline(target));
        } catch (Throwable throwable) {
            releaseImmediately = true;
            return CompletableFuture.failedFuture(throwable);
        } finally {
            if (releaseImmediately) {
                SchedulerThread.INSTANCE.leaveChunkPipeline(target);
            }
        }
    }

    public enum ChunkStatusThreadingType {

        PARALLELIZED() {
            @Override
            public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture) {
                return CompletableFuture.supplyAsync(completableFuture, GlobalExecutors.executor).thenCompose(Function.identity());
            }
        },
        SINGLE_THREADED() {
            @Override
            public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture) {
                Preconditions.checkNotNull(lock);
                return lock.acquireLock().toCompletableFuture().thenComposeAsync(lockToken -> {
                    CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> stage;
                    try {
                        stage = completableFuture.get();
                    } catch (Throwable throwable) {
                        lockToken.releaseLock();
                        return CompletableFuture.failedFuture(throwable);
                    }
                    if (stage == null) {
                        lockToken.releaseLock();
                        return CompletableFuture.failedFuture(new NullPointerException("single-thread stage"));
                    }
                    return stage.whenComplete((result, throwable) -> lockToken.releaseLock());
                }, GlobalExecutors.executor);
            }
        },
        AS_IS() {
            @Override
            public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture) {
                return completableFuture.get();
            }
        };

        public abstract CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> runTask(AsyncLock lock, Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> completableFuture);

    }
}
