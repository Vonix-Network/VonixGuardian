package network.vonix.threadedhorizons.mixin.threading.chunkio;

import com.ibm.asyncutil.locks.AsyncNamedLock;
import network.vonix.threadedhorizons.common.GlobalExecutors;
import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerations;
import network.vonix.threadedhorizons.common.threading.chunkio.ChunkIoMainThreadTaskUtils;
import network.vonix.threadedhorizons.common.threading.chunkio.ChunkLoadException;
import network.vonix.threadedhorizons.common.threading.chunkio.ChunkLoadResult;
import network.vonix.threadedhorizons.common.threading.chunkio.CombinedChunkLoad;
import network.vonix.threadedhorizons.common.threading.chunkio.IAsyncChunkStorage;
import network.vonix.threadedhorizons.common.threading.chunkio.ISerializingRegionBasedStorage;
import network.vonix.threadedhorizons.common.util.SneakyThrow;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Registry;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage extends ChunkStorage implements ChunkHolder.PlayerProvider {

    public MixinThreadedAnvilChunkStorage(Path path, DataFixer dataFixer, boolean bl) {
        super(path, dataFixer, bl);
    }

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private StructureManager structureManager;

    @Shadow
    @Final
    private PoiManager poiManager;

    @Shadow
    protected abstract byte markPosition(ChunkPos chunkPos, ChunkStatus.ChunkType chunkType);

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    protected abstract void markPositionReplaceable(ChunkPos chunkPos);

    @Shadow
    @Final
    private Supplier<DimensionDataStorage> overworldDataStorage;

    @Shadow
    @Final
    private BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow
    protected abstract boolean isExistingChunkFull(ChunkPos chunkPos);

    @Shadow private ChunkGenerator generator;

    private AsyncNamedLock<ChunkPos> chunkLock = AsyncNamedLock.createFair();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        chunkLock = AsyncNamedLock.createFair();
    }

    private Set<ChunkPos> scheduledChunks = new HashSet<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> saveFutures = new ConcurrentLinkedQueue<>();

    /**
     * @author vonix
     * @reason Fail closed for present-but-unreadable chunks. Bind POI work to the load transaction.
     */
    @Overwrite
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkLoad(ChunkPos pos) {
        if (scheduledChunks == null) scheduledChunks = new HashSet<>();
        synchronized (scheduledChunks) {
            if (scheduledChunks.contains(pos)) throw new IllegalArgumentException("Already scheduled");
            scheduledChunks.add(pos);
        }

        final CompletableFuture<CompoundTag> poiData = ((IAsyncChunkStorage) this.poiManager.worker).getNbtAtAsync(pos);

        final CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = getUpdatedChunkNbtAtAsync(pos).thenApplyAsync(compoundTag -> {
            if (compoundTag == null) {
                return new ChunkLoadResult(false, null, new ChunkIoMainThreadTaskUtils.Transaction());
            }
            if (!compoundTag.contains("Status", 8)) {
                throw new ChunkLoadException(pos, ChunkLoadException.Kind.MISSING_STATUS,
                        "Chunk at " + pos + " is present but missing Status");
            }
            ChunkIoMainThreadTaskUtils.Transaction transaction = ChunkIoMainThreadTaskUtils.open();
            try {
                return new ChunkLoadResult(true, ChunkSerializer.read(this.level, this.poiManager, pos, compoundTag), transaction);
            } catch (ChunkLoadException loadException) {
                throw loadException;
            } catch (Throwable throwable) {
                throw new ChunkLoadException(pos, ChunkLoadException.Kind.DESERIALIZE,
                        "Could not deserialize chunk " + pos, throwable);
            } finally {
                ChunkIoMainThreadTaskUtils.pop();
            }
        }, GlobalExecutors.executor).thenCombine(poiData, CombinedChunkLoad::new).thenApplyAsync(combined -> {
            try {
                ((ISerializingRegionBasedStorage) this.poiManager).update(pos, combined.poiTag());
                combined.result().transaction().drainOrThrow();
            } catch (ChunkLoadException loadException) {
                throw loadException;
            } catch (Throwable throwable) {
                throw new ChunkLoadException(pos, ChunkLoadException.Kind.POI, "POI update failed for " + pos, throwable);
            }
            if (combined.result().present()) {
                if (combined.result().chunk() == null) {
                    throw new ChunkLoadException(pos, ChunkLoadException.Kind.DESERIALIZE,
                            "Present chunk " + pos + " deserialized to empty");
                }
                this.markPosition(pos, combined.result().chunk().getStatus().getChunkType());
                return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(combined.result().chunk());
            }
            this.markPositionReplaceable(pos);
            return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(
                    new ProtoChunk(pos, UpgradeData.EMPTY, this.level, this.level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), null));
        }, this.mainThreadExecutor);
        future.whenComplete((ignored, throwable) -> {
            synchronized (scheduledChunks) {
                scheduledChunks.remove(pos);
            }
            if (throwable != null) {
                LOGGER.error("Refusing to replace unreadable chunk {}", pos, throwable);
            }
        });
        return future;
    }

    private CompletableFuture<CompoundTag> getUpdatedChunkNbtAtAsync(ChunkPos pos) {
        return chunkLock.acquireLock(pos).toCompletableFuture().thenCompose(lockToken -> ((IAsyncChunkStorage) this.worker).getNbtAtAsync(pos).thenApply(compoundTag -> {
            if (compoundTag != null) {
                try {
                    return this.upgradeChunkTag(this.level.dimension(), this.overworldDataStorage, compoundTag, this.generator.getTypeNameForDataFixer());
                } catch (Throwable throwable) {
                    throw new ChunkLoadException(pos, ChunkLoadException.Kind.DATA_FIX, "Data-fix failed for " + pos, throwable);
                }
            }
            return null;
        }).handle((tag, throwable) -> {
            lockToken.releaseLock();
            if (throwable != null) {
                SneakyThrow.sneaky(throwable);
            }
            return tag;
        }));
    }

    /**
     * @author vonix
     * @reason Keep dirty generations until the store future is durable. Unload uses this remappable save path.
     */
    @Overwrite
    private boolean save(ChunkAccess chunk) {
        this.poiManager.flush(chunk.getPos());
        if (!chunk.isUnsaved()) {
            return false;
        }
        long generation = DirtyChunkGenerations.captureForSave(chunk);
        ChunkPos chunkPos = chunk.getPos();
        try {
            ChunkStatus chunkStatus = chunk.getStatus();
            if (chunkStatus.getChunkType() != ChunkStatus.ChunkType.LEVELCHUNK) {
                if (this.isExistingChunkFull(chunkPos)) {
                    return false;
                }
                if (chunkStatus == ChunkStatus.EMPTY && chunk.getAllStarts().values().stream().noneMatch(StructureStart::isValid)) {
                    return false;
                }
            }
            this.level.getProfiler().push("chunkSave");
            CompoundTag compoundTag = ChunkSerializer.write(this.level, chunk);
            CompletableFuture<Void> stored = this.worker.store(chunkPos, compoundTag);
            this.saveFutures.add(stored);
            stored.whenComplete((unused, throwable) -> {
                DirtyChunkGenerations.applyStoreOutcome(chunk, generation, throwable);
                if (throwable != null) {
                    LOGGER.error("Failed to store chunk {},{}", chunkPos.x, chunkPos.z, throwable);
                }
            });
            try {
                stored.join();
            } catch (Throwable throwable) {
                DirtyChunkGenerations.applyStoreOutcome(chunk, generation, throwable);
                this.level.getProfiler().pop();
                return false;
            }
            this.markPosition(chunkPos, chunkStatus.getChunkType());
            this.level.getProfiler().pop();
            return !stored.isCompletedExceptionally();
        } catch (Exception exception) {
            DirtyChunkGenerations.applyStoreOutcome(chunk, generation, exception);
            LOGGER.error("Failed to save chunk {},{}", chunkPos.x, chunkPos.z, exception);
            return false;
        }
    }

    @Inject(method = "processUnloads", at = @At("RETURN"))
    private void awaitUnloadedStores(BooleanSupplier keepGoing, CallbackInfo info) {
        joinSaveFutures();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo info) {
        this.saveFutures.removeIf(CompletableFuture::isDone);
    }

    @Override
    public void flushWorker() {
        joinSaveFutures();
        super.flushWorker();
    }

    private void joinSaveFutures() {
        final CompletableFuture<Void> future = CompletableFuture.allOf(this.saveFutures.toArray(new CompletableFuture[0]));
        this.mainThreadExecutor.managedBlock(future::isDone);
    }
}
