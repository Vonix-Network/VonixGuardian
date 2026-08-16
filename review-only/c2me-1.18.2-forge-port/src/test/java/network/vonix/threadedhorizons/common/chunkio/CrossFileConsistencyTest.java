package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(60)
class CrossFileConsistencyTest {

    @TempDir
    Path root;

    @Test
    void chunkPoiEntityAndStructureRecordsStayAlignedAcrossIndependentReaders() throws Exception {
        Path chunkDir = this.root.resolve("region");
        Path poiDir = this.root.resolve("poi");
        Path entityDir = this.root.resolve("entities");
        ChunkPos pos = new ChunkPos(3, 7);
        UUID entityId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        CompoundTag chunk = chunkTag(pos, entityId, "village");
        CompoundTag poi = poiTag(pos, "minecraft:home");
        CompoundTag entity = entityTag(pos, entityId);

        writeClosed(chunkDir, pos, chunk);
        writeClosed(poiDir, pos, poi);
        writeClosed(entityDir, pos, entity);

        assertEquals(chunk, VanillaRegionCycleTest.readWithStockRegionFile(chunkDir, pos));
        assertEquals(poi, VanillaRegionCycleTest.readWithStockRegionFile(poiDir, pos));
        assertEquals(entity, VanillaRegionCycleTest.readWithStockRegionFile(entityDir, pos));
        assertEquals(entityId, VanillaRegionCycleTest.readWithStockRegionFile(entityDir, pos)
                .getUUID("UUID"));
        assertEquals("village", VanillaRegionCycleTest.readWithStockRegionFile(chunkDir, pos)
                .getCompound("Structures").getString("Name"));
        assertEquals("minecraft:home", VanillaRegionCycleTest.readWithStockRegionFile(poiDir, pos)
                .getString("Type"));
    }

    @Test
    void oneSidedPoiFailureLeavesPriorConsistentRecords() throws Exception {
        Path chunkDir = this.root.resolve("fail-region");
        Path poiDir = this.root.resolve("fail-poi");
        ChunkPos pos = new ChunkPos(1, 1);
        CompoundTag chunk = chunkTag(pos, UUID.fromString("11111111-2222-3333-4444-555555555555"), "fortress");
        CompoundTag poi = poiTag(pos, "minecraft:nether_portal");
        writeClosed(chunkDir, pos, chunk);
        writeClosed(poiDir, pos, poi);

        ExecutorService serialize = Executors.newSingleThreadExecutor();
        FaultyRegionBackend poiBackend = new FaultyRegionBackend(new VanillaRegionBackend(poiDir, true));
        poiBackend.failWrites = true;
        ThreadedHorizonsStorageThread poiStore = new ThreadedHorizonsStorageThread(poiBackend, serialize, "poi-fail");
        try {
            CompoundTag newer = poiTag(pos, "minecraft:meeting");
            assertThrows(CompletionException.class, () -> poiStore.store(pos.toLong(), newer).join());
            assertThrows(Exception.class, () -> poiStore.close().join());
        } finally {
            serialize.shutdownNow();
        }

        assertEquals(chunk, VanillaRegionCycleTest.readWithStockRegionFile(chunkDir, pos));
        CompoundTag reopenedPoi = VanillaRegionCycleTest.readWithStockRegionFile(poiDir, pos);
        assertNotNull(reopenedPoi);
        assertEquals("minecraft:nether_portal", reopenedPoi.getString("Type"));
        assertNull(VanillaRegionCycleTest.readWithStockRegionFile(this.root.resolve("missing"), pos));
    }

    private static void writeClosed(Path directory, ChunkPos pos, CompoundTag tag) throws Exception {
        ExecutorService serialize = Executors.newSingleThreadExecutor();
        ThreadedHorizonsStorageThread storage = new ThreadedHorizonsStorageThread(
                new VanillaRegionBackend(directory, true), serialize, directory.getFileName().toString());
        try {
            storage.store(pos.toLong(), tag).get(10, TimeUnit.SECONDS);
            storage.flush(true).get(10, TimeUnit.SECONDS);
        } finally {
            storage.close().get(10, TimeUnit.SECONDS);
            serialize.shutdownNow();
        }
    }

    private static CompoundTag chunkTag(ChunkPos pos, UUID entityId, String structure) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "full");
        chunk.putInt("xPos", pos.x);
        chunk.putInt("zPos", pos.z);
        ListTag entities = new ListTag();
        CompoundTag entity = new CompoundTag();
        entity.putUUID("UUID", entityId);
        entity.putString("id", "minecraft:villager");
        entities.add(entity);
        chunk.put("Entities", entities);
        CompoundTag structures = new CompoundTag();
        structures.putString("Name", structure);
        structures.putLong("References", pos.toLong());
        chunk.put("Structures", structures);
        return chunk;
    }

    private static CompoundTag poiTag(ChunkPos pos, String type) {
        CompoundTag poi = new CompoundTag();
        poi.putString("Type", type);
        poi.putInt("x", pos.getMinBlockX());
        poi.putInt("z", pos.getMinBlockZ());
        poi.putBoolean("Occupied", true);
        return poi;
    }

    private static final class FaultyRegionBackend implements RegionBackend {
        private final RegionBackend inner;
        volatile boolean failWrites;

        private FaultyRegionBackend(RegionBackend inner) {
            this.inner = inner;
        }

        @Override
        public CompoundTag read(ChunkPos pos) throws java.io.IOException {
            return this.inner.read(pos);
        }

        @Override
        public void write(ChunkPos pos, CompoundTag nbt) throws java.io.IOException {
            if (this.failWrites) {
                throw new java.io.IOException("poi write blocked");
            }
            this.inner.write(pos, nbt);
        }

        @Override
        public void clear(ChunkPos pos) throws java.io.IOException {
            this.inner.clear(pos);
        }

        @Override
        public void scan(ChunkPos pos, net.minecraft.nbt.StreamTagVisitor visitor) throws java.io.IOException {
            this.inner.scan(pos, visitor);
        }

        @Override
        public void flush() throws java.io.IOException {
            this.inner.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            this.inner.close();
        }
    }

    private static CompoundTag entityTag(ChunkPos pos, UUID entityId) {
        CompoundTag entity = new CompoundTag();
        entity.putUUID("UUID", entityId);
        entity.putString("id", "minecraft:villager");
        entity.putInt("ChunkX", pos.x);
        entity.putInt("ChunkZ", pos.z);
        return entity;
    }
}
