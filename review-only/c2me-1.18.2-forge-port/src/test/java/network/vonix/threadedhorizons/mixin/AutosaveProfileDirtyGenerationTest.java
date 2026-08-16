package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.chunkio.DirtyChunkGenerationsTest;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig.GeneralOptimizationsConfig.AutoSaveConfig.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AutosaveProfileDirtyGenerationTest {

    private static final String CHUNK_ACCESS = "net.minecraft.world.level.chunk.ChunkAccess";
    private static final String LEVEL_CHUNK = "net.minecraft.world.level.chunk.LevelChunk";
    private static final String MUTATION_CHUNK =
            "network.vonix.threadedhorizons.mixin.threading.chunkio.MixinChunkDirtyGenerations";
    private static final String MUTATION_WORLD_CHUNK =
            "network.vonix.threadedhorizons.mixin.threading.chunkio.MixinWorldChunkDirtyGenerations";
    private static final String ASYNC_SAVE =
            "network.vonix.threadedhorizons.mixin.threading.chunkio.MixinThreadedAnvilChunkStorage";
    private static final String ENHANCED_CHUNK =
            "network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.MixinChunk";
    private static final String ENHANCED_WORLD_CHUNK =
            "network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.MixinWorldChunk";
    private static final String ENHANCED_STORAGE =
            "network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.MixinThreadedAnvilChunkStorage";

    @Test
    void mutationBetweenValidationAndClearSurvivesEveryAutosaveProfile() throws Exception {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        for (Mode mode : Mode.values()) {
            assertTrue(plugin.evaluate(CHUNK_ACCESS, MUTATION_CHUNK, true, mode), mode + " chunk mutation mixin");
            assertTrue(plugin.evaluate(LEVEL_CHUNK, MUTATION_WORLD_CHUNK, true, mode), mode + " world-chunk mutation mixin");
            assertTrue(plugin.evaluate("net.minecraft.server.level.ChunkMap", ASYNC_SAVE, true, mode),
                    mode + " async save overwrite");
            assertFalse(plugin.evaluate(CHUNK_ACCESS, MUTATION_CHUNK, false, mode), mode + " async-off chunk mixin");
            assertFalse(plugin.evaluate(LEVEL_CHUNK, MUTATION_WORLD_CHUNK, false, mode), mode + " async-off world-chunk mixin");
            assertEquals(mode == Mode.ENHANCED, plugin.evaluate(CHUNK_ACCESS, ENHANCED_CHUNK, true, mode), mode + " enhanced chunk");
            assertEquals(mode == Mode.ENHANCED, plugin.evaluate(LEVEL_CHUNK, ENHANCED_WORLD_CHUNK, true, mode), mode + " enhanced world-chunk");
            assertEquals(mode == Mode.ENHANCED, plugin.evaluate("net.minecraft.server.level.ChunkMap", ENHANCED_STORAGE, true, mode),
                    mode + " enhanced storage");
            DirtyChunkGenerationsTest.assertMutationBetweenValidationAndClearSurvives(mode.name());
        }
    }
}
