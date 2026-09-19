package network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.optimization.chunkscheduling.idle_tasks.IThreadedAnvilChunkStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage implements IThreadedAnvilChunkStorage {

    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow protected abstract boolean saveChunkIfNeeded(ChunkHolder chunkHolder);

    @Unique
    private final Object2LongLinkedOpenHashMap<ChunkPos> dirtyChunkPosForAutoSave = new Object2LongLinkedOpenHashMap<>();

    @Override
    public void enqueueDirtyChunkPosForAutoSave(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return;
        }
        synchronized (this.dirtyChunkPosForAutoSave) {
            this.dirtyChunkPosForAutoSave.putAndMoveToLast(chunkPos, System.currentTimeMillis());
        }
    }

    @Override
    public boolean runOneChunkAutoSave() {
        synchronized (this.dirtyChunkPosForAutoSave) {
            final ObjectBidirectionalIterator<Object2LongMap.Entry<ChunkPos>> iterator = this.dirtyChunkPosForAutoSave.object2LongEntrySet().fastIterator();
            while (iterator.hasNext()) {
                final Object2LongMap.Entry<ChunkPos> entry = iterator.next();
                if (System.currentTimeMillis() - entry.getLongValue() < ThreadedHorizonsConfig.generalOptimizationsConfig.autoSaveConfig.delay) break;
                if (entry.getKey() == null) {
                    iterator.remove();
                    continue;
                }
                ChunkHolder chunkHolder = this.updatingChunkMap.get(entry.getKey().toLong());
                if (chunkHolder == null) {
                    iterator.remove();
                    continue;
                }
                final CompletableFuture<ChunkAccess> savingFuture = chunkHolder.getChunkToSave();
                if (!savingFuture.isDone()) {
                    savingFuture.handle((chunk, throwable) -> null);
                    continue;
                }
                boolean scheduled = this.saveChunkIfNeeded(chunkHolder);
                ChunkAccess chunk = savingFuture.getNow(null);
                if (scheduled || (chunk != null && chunk.isUnsaved())) {
                    if (scheduled) {
                        iterator.remove();
                    }
                    return true;
                }
                iterator.remove();
            }
        }

        return false;
    }
}
