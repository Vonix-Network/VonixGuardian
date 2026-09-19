package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(180)
class StorageLoadQueueBoundTest {

    @TempDir
    Path root;

    @Test
    void threeDimensionBurstDrainsWithoutLeakingFutures() throws Exception {
        String[] dimensions = {"overworld", "nether", "end"};
        List<ThreadedHorizonsStorageThread> threads = new ArrayList<>();
        List<ExecutorService> pools = new ArrayList<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        try {
            for (String dimension : dimensions) {
                ExecutorService pool = Executors.newFixedThreadPool(4);
                pools.add(pool);
                ThreadedHorizonsStorageThread storage = new ThreadedHorizonsStorageThread(
                        new VanillaRegionBackend(this.root.resolve(dimension), true), pool, "load-" + dimension);
                threads.add(storage);
                for (int i = 0; i < 256; i++) {
                    long pos = new ChunkPos(i, dimension.hashCode() & 15).toLong();
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("v", i);
                    tag.putString("Dimension", dimension);
                    futures.add(storage.store(pos, tag).thenRun(accepted::incrementAndGet));
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
            for (ThreadedHorizonsStorageThread storage : threads) {
                storage.flush(true).get(30, TimeUnit.SECONDS);
            }
            assertEquals(256 * dimensions.length, accepted.get());
            for (int index = 0; index < dimensions.length; index++) {
                ThreadedHorizonsStorageThread storage = threads.get(index);
                String dimension = dimensions[index];
                CompoundTag read = storage.getChunkData(new ChunkPos(0, dimension.hashCode() & 15).toLong(), null)
                        .get(10, TimeUnit.SECONDS);
                assertEquals(0, read.getInt("v"));
                assertEquals(dimension, read.getString("Dimension"));
            }
        } finally {
            for (ThreadedHorizonsStorageThread storage : threads) {
                storage.close().get(15, TimeUnit.SECONDS);
                assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
            }
            for (ExecutorService pool : pools) {
                pool.shutdownNow();
            }
        }
    }
}
