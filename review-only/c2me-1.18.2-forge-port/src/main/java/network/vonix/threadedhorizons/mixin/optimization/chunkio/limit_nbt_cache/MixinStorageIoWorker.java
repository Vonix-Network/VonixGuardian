package network.vonix.threadedhorizons.mixin.optimization.chunkio.limit_nbt_cache;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

@Mixin(value = IOWorker.class, priority = 990)
public abstract class MixinStorageIoWorker {

    @Shadow @Final private Map<ChunkPos, IOWorker.PendingStore> pendingWrites;

    @Shadow protected abstract void runStore(ChunkPos pos, IOWorker.PendingStore result);

    @Shadow protected abstract void storePendingChunk();

    @Shadow @Final private static Logger LOGGER;

    /**
     * Yarn {@code method_27939} is not a 1.18.2 official member. Official
     * {@code tellStorePending} ({@code m_63561_}) runs on the IO mailbox after
     * each submitted task. Do not inject into public {@code submitTask}: that
     * method is called from the server thread and would mutate {@code pendingWrites}
     * while {@code synchronize} iterates it.
     */
    @Inject(method = "tellStorePending", at = @At("HEAD"))
    private void preTask(CallbackInfo ci) {
        checkHardLimit();
    }

    /**
     * Yarn {@code writeResult} is official {@code storePendingChunk} ({@code m_63553_}).
     * Public {@code store} only enqueues work and must not drain the map off-thread.
     */
    @Inject(method = "storePendingChunk", at = @At("HEAD"))
    private void onWriteResult(CallbackInfo ci) {
        if (!this.pendingWrites.isEmpty()) {
            checkHardLimit();
            if (this.pendingWrites.size() >= ThreadedHorizonsConfig.ioSystemConfig.chunkDataCacheSoftLimit) {
                int writeFrequency = Math.min(1, (this.pendingWrites.size() - ThreadedHorizonsConfig.ioSystemConfig.chunkDataCacheSoftLimit) / 16);
                for (int i = 0; i < writeFrequency; i++) {
                    writeResult0();
                }
            }
        }
    }

    @Unique
    private void checkHardLimit() {
        if (this.pendingWrites.size() >= ThreadedHorizonsConfig.ioSystemConfig.chunkDataCacheLimit) {
            LOGGER.warn("ChunkAccess data cache size exceeded hard limit ({} >= {}), forcing writes to disk (you can increase chunkDataCacheLimit in threadedhorizons.toml)", this.pendingWrites.size(), ThreadedHorizonsConfig.ioSystemConfig.chunkDataCacheLimit);
            while (this.pendingWrites.size() >= ThreadedHorizonsConfig.ioSystemConfig.chunkDataCacheSoftLimit * 0.75) { // using chunkDataCacheSoftLimit is intentional
                writeResult0();
            }
        }
    }

    @Unique
    private void writeResult0() {
        Iterator<Map.Entry<ChunkPos, IOWorker.PendingStore>> iterator = this.pendingWrites.entrySet().iterator();
        if (iterator.hasNext()) {
            Map.Entry<ChunkPos, IOWorker.PendingStore> entry = iterator.next();
            iterator.remove();
            this.runStore(entry.getKey(), entry.getValue());
        }
    }

}
