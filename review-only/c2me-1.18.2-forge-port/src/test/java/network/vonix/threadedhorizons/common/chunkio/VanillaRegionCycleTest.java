package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(180)
class VanillaRegionCycleTest {

    private static final String[] DIMENSIONS = {"overworld", "nether", "end"};
    private static final int CYCLES = 100;

    @TempDir
    Path root;

    @Test
    void oneHundredSaveStopReopenCyclesAcrossThreeDimensions() throws Exception {
        Map<String, CompoundTag> expected = new HashMap<>();
        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            for (String dimension : DIMENSIONS) {
                Path directory = this.root.resolve(dimension);
                CompoundTag payload = fixture(dimension, cycle);
                expected.put(dimension, payload.copy());
                ExecutorService serialize = Executors.newSingleThreadExecutor();
                ThreadedHorizonsStorageThread storage = new ThreadedHorizonsStorageThread(
                        new VanillaRegionBackend(directory, true), serialize, "cycle-" + dimension + "-" + cycle);
                try {
                    storage.store(pos(dimension).toLong(), payload).get(10, TimeUnit.SECONDS);
                    storage.flush(true).get(10, TimeUnit.SECONDS);
                } finally {
                    storage.close().get(10, TimeUnit.SECONDS);
                    assertTrue(storage.awaitTermination(5, TimeUnit.SECONDS));
                    serialize.shutdownNow();
                }
            }
            for (String dimension : DIMENSIONS) {
                Path directory = this.root.resolve(dimension);
                CompoundTag viaBackend;
                try (VanillaRegionBackend backend = new VanillaRegionBackend(directory, true)) {
                    viaBackend = backend.read(pos(dimension));
                }
                assertNotNull(viaBackend, "cycle " + cycle + " " + dimension);
                assertEquals(expected.get(dimension), viaBackend, "backend " + dimension + " cycle " + cycle);
                assertEquals(expected.get(dimension), readWithStockRegionFile(directory, pos(dimension)),
                        "stock reader " + dimension + " cycle " + cycle);
            }
        }
    }

    private static CompoundTag fixture(String dimension, int cycle) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "full");
        chunk.putString("Dimension", dimension);
        chunk.putInt("Cycle", cycle);
        chunk.putInt("xPos", pos(dimension).x);
        chunk.putInt("zPos", pos(dimension).z);
        ListTag entities = new ListTag();
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:marker");
        entity.putUUID("UUID", java.util.UUID.nameUUIDFromBytes((dimension + cycle).getBytes()));
        entities.add(entity);
        chunk.put("Entities", entities);
        CompoundTag structures = new CompoundTag();
        structures.putLong("References", pos(dimension).toLong());
        chunk.put("Structures", structures);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        blockEntity.putInt("x", pos(dimension).getMinBlockX());
        blockEntity.putInt("z", pos(dimension).getMinBlockZ());
        ListTag blockEntities = new ListTag();
        blockEntities.add(blockEntity);
        chunk.put("block_entities", blockEntities);
        return chunk;
    }

    private static ChunkPos pos(String dimension) {
        return switch (dimension) {
            case "nether" -> new ChunkPos(8, -3);
            case "end" -> new ChunkPos(-4, 12);
            default -> new ChunkPos(2, 5);
        };
    }

    static CompoundTag readWithStockRegionFile(Path directory, ChunkPos pos) throws Exception {
        try (RegionFileStorage storage = new RegionFileStorage(directory, true)) {
            RegionFile regionFile = storage.getRegionFile(pos);
            DataInputStream input = regionFile.getChunkDataInputStream(pos);
            if (input == null) {
                return null;
            }
            try (DataInputStream stream = input) {
                return NbtIo.read(stream);
            }
        }
    }
}
