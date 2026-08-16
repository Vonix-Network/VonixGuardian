package network.vonix.threadedhorizons.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialMixinTargetPresenceTest {

    @Test
    void official1832ChunkTickTargetsExist() throws Exception {
        assertNotNull(declared(ServerChunkCache.class, "tickChunks"));
        assertNotNull(declared(ServerChunkCache.class, "isPositionTicking", long.class));
        assertEquals(CompletableFuture.class, declared(ChunkHolder.class, "getTickingChunkFuture").getReturnType());
        assertEquals(CompletableFuture.class, declared(ChunkHolder.class, "getFullChunkFuture").getReturnType());
        assertEquals(LevelChunk.class, declared(ChunkHolder.class, "getTickingChunk").getReturnType());
        assertEquals(Iterable.class, declared(ServerLevel.class, "getAllEntities").getReturnType());
        assertNotNull(declared(ServerLevel.class, "tickChunk", LevelChunk.class, int.class));
    }

    @Test
    void official1832SaveAndTicketTargetsExist() throws Exception {
        assertTrue(Modifier.isPrivate(declared(ChunkMap.class, "save", net.minecraft.world.level.chunk.ChunkAccess.class).getModifiers()));
        assertNotNull(declared(ChunkMap.class, "processUnloads", java.util.function.BooleanSupplier.class));
        assertNotNull(declared(ChunkMap.class, "prepareAccessibleChunk", ChunkHolder.class));
        assertNotNull(declared(DistanceManager.class, "updateSimulationDistance", int.class));
        assertNotNull(declared(DistanceManager.class, "updatePlayerTickets", int.class));
        assertNotNull(declared(DistanceManager.class, "purgeStaleTickets"));
        assertNotNull(declared(IOWorker.class, "storePendingChunk"));
        assertNotNull(declared(IOWorker.class, "tellStorePending"));
        assertNotNull(declared(RegionFile.class, "getChunkDataInputStream", net.minecraft.world.level.ChunkPos.class));
        assertNotNull(declared(RegionFile.class, "getChunkDataOutputStream", net.minecraft.world.level.ChunkPos.class));
        assertNotNull(declared(RegionFile.class, "clear", net.minecraft.world.level.ChunkPos.class));
        assertNotNull(declared(FeaturePlaceContext.class, "level"));
        assertNotNull(declared(FeaturePlaceContext.class, "random"));
        assertNotNull(declared(
                net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement.class,
                "getSettings",
                net.minecraft.world.level.block.Rotation.class,
                net.minecraft.world.level.levelgen.structure.BoundingBox.class,
                boolean.class));
        assertNotNull(declared(
                net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement.class,
                "place",
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager.class,
                net.minecraft.world.level.WorldGenLevel.class,
                net.minecraft.world.level.StructureFeatureManager.class,
                net.minecraft.world.level.chunk.ChunkGenerator.class,
                net.minecraft.core.BlockPos.class,
                net.minecraft.core.BlockPos.class,
                net.minecraft.world.level.block.Rotation.class,
                net.minecraft.world.level.levelgen.structure.BoundingBox.class,
                java.util.Random.class,
                boolean.class));
    }

    @Test
    void defaultRequireRemainsOne() throws Exception {
        String json = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/threadedhorizons.mixins.json"));
        assertTrue(json.contains("\"defaultRequire\": 1"));
        assertFalse(json.contains("\"defaultRequire\": 0"));
    }

    private static Method declared(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        assertEquals(name, method.getName());
        assertTrue(Arrays.equals(parameters, method.getParameterTypes()));
        return method;
    }
}
