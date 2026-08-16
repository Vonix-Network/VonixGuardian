package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.client.mixin.ThreadedHorizonsClientMixinPlugin;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinProfileMatrixTest {

    @Test
    void serverPluginHonorsFeatureGates() {
        ThreadedHorizonsMixinPlugin plugin = new ThreadedHorizonsMixinPlugin();
        plugin.onLoad("network.vonix.threadedhorizons.mixin");

        assertEquals(
                ThreadedHorizonsConfig.noTickViewDistanceConfig.enabled,
                plugin.shouldApplyMixin("net.minecraft.server.level.ServerChunkCache",
                        "network.vonix.threadedhorizons.mixin.notickvd.MixinServerChunkManager"));
        assertEquals(
                ThreadedHorizonsConfig.asyncSchedulingConfig.enabled,
                plugin.shouldApplyMixin("net.minecraft.server.level.ChunkMap",
                        "network.vonix.threadedhorizons.mixin.threading.async_scheduling.MixinThreadedAnvilChunkStorage"));
        assertEquals(
                ThreadedHorizonsConfig.ioSystemConfig.async,
                plugin.shouldApplyMixin("net.minecraft.server.level.ChunkMap",
                        "network.vonix.threadedhorizons.mixin.threading.chunkio.MixinThreadedAnvilChunkStorage"));
        assertEquals(
                ThreadedHorizonsConfig.ioSystemConfig.replaceImpl,
                plugin.shouldApplyMixin("net.minecraft.world.level.chunk.storage.IOWorker",
                        "network.vonix.threadedhorizons.mixin.chunkio.MixinStorageIoWorker"));
        assertEquals(
                ThreadedHorizonsConfig.threadedWorldGenConfig.enabled,
                plugin.shouldApplyMixin("net.minecraft.world.level.chunk.ChunkGenerator",
                        "network.vonix.threadedhorizons.mixin.threading.worldgen.MixinNoiseChunkGenerator"));
        assertEquals(
                ThreadedHorizonsConfig.threadedWorldGenConfig.enabled
                        && ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache,
                plugin.shouldApplyMixin("net.minecraft.world.level.biome.BiomeSource",
                        "network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache.MixinBiomeSource"));
        assertEquals(
                !(ThreadedHorizonsConfig.threadedWorldGenConfig.enabled
                        && ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache),
                plugin.shouldApplyMixin("net.minecraft.world.level.biome.MultiNoiseBiomeSource$Preset",
                        "network.vonix.threadedhorizons.mixin.optimization.worldgen.thread_local_biome_cache.MixinMultiNoiseBiomeSourcePreset"));
        assertEquals(
                ThreadedHorizonsConfig.generalOptimizationsConfig.doMidTickChunkTasks,
                plugin.shouldApplyMixin("net.minecraft.server.level.ServerChunkCache",
                        "network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.mid_tick_chunk_tasks.MixinServerChunkManager"));
        assertTrue(plugin.shouldApplyMixin("net.minecraft.server.MinecraftServer",
                "network.vonix.threadedhorizons.mixin.failsafe.MixinThreadedAnvilChunkStorage"));
        assertFalse(ThreadedHorizonsConfig.asyncSchedulingConfig.enabled,
                "async scheduling must stay off by default on this candidate");
        assertFalse(ThreadedHorizonsConfig.ioSystemConfig.replaceImpl,
                "replaceImpl must stay off by default");
        assertFalse(ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache,
                "global biome cache must stay off");
        assertEquals(
                ThreadedHorizonsConfig.ioSystemConfig.async,
                plugin.shouldApplyMixin("net.minecraft.world.level.chunk.ChunkAccess",
                        "network.vonix.threadedhorizons.mixin.threading.chunkio.MixinChunkDirtyGenerations"));
        assertEquals(
                ThreadedHorizonsConfig.ioSystemConfig.async,
                plugin.shouldApplyMixin("net.minecraft.world.level.chunk.LevelChunk",
                        "network.vonix.threadedhorizons.mixin.threading.chunkio.MixinWorldChunkDirtyGenerations"));
        assertEquals(
                ThreadedHorizonsConfig.generalOptimizationsConfig.autoSaveConfig.mode
                        == ThreadedHorizonsConfig.GeneralOptimizationsConfig.AutoSaveConfig.Mode.ENHANCED,
                plugin.shouldApplyMixin("net.minecraft.world.level.chunk.ChunkAccess",
                        "network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.MixinChunk"));
    }

    @Test
    void dedicatedServerDoesNotApplyClientUncapMixins() {
        ThreadedHorizonsClientMixinPlugin plugin = new ThreadedHorizonsClientMixinPlugin();
        assertFalse(plugin.shouldApplyMixin("net.minecraft.client.Options",
                "network.vonix.threadedhorizons.client.mixin.uncapvd.MixinGameOptions"));
    }
}
