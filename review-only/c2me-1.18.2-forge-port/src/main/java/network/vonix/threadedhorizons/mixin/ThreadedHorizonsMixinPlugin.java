package network.vonix.threadedhorizons.mixin;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig.GeneralOptimizationsConfig.AutoSaveConfig.Mode;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ThreadedHorizonsMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Mixin");
    private static final Set<String> LOGGED_SKIPS = ConcurrentHashMap.newKeySet();

    @Override
    public void onLoad(String mixinPackage) {
        //noinspection ResultOfMethodCallIgnored
        ThreadedHorizonsConfig.threadedWorldGenConfig.getClass().getName(); // Load configuration
        LOGGER.info("Successfully loaded configuration for Threaded Horizons");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = evaluate(targetClassName, mixinClassName);
        if (!apply && LOGGED_SKIPS.add(mixinClassName)) {
            LOGGER.info("TH_MIXIN_SKIPPED {} -> {}", mixinClassName, targetClassName);
        }
        return apply;
    }

    private boolean evaluate(String targetClassName, String mixinClassName) {
        return evaluate(
                targetClassName,
                mixinClassName,
                ThreadedHorizonsConfig.ioSystemConfig.async,
                ThreadedHorizonsConfig.generalOptimizationsConfig.autoSaveConfig.mode,
                LoaderHooks.isLithiumFamilyLoaded()
        );
    }

    boolean evaluate(String targetClassName, String mixinClassName, boolean async, Mode mode) {
        return evaluate(targetClassName, mixinClassName, async, mode, LoaderHooks.isLithiumFamilyLoaded());
    }

    boolean evaluate(String targetClassName, String mixinClassName, boolean async, Mode mode, boolean lithiumFamilyLoaded) {
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.threading.worldgen."))
            return ThreadedHorizonsConfig.threadedWorldGenConfig.enabled;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.threading.chunkio."))
            return async;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache."))
            return ThreadedHorizonsConfig.threadedWorldGenConfig.enabled && ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.worldgen.thread_local_biome_cache."))
            return !(ThreadedHorizonsConfig.threadedWorldGenConfig.enabled && ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache);
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.worldgen.vanilla_optimization.the_end_biome_cache."))
            return ThreadedHorizonsConfig.vanillaWorldGenOptimizationsConfig.useEndBiomeCache;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.chunkaccess.async_chunk_request."))
            return ThreadedHorizonsConfig.generalOptimizationsConfig.optimizeAsyncChunkRequest;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.notickvd."))
            return ThreadedHorizonsConfig.noTickViewDistanceConfig.enabled;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.chunkio.compression.modify_default_chunk_compression."))
            return ThreadedHorizonsConfig.generalOptimizationsConfig.chunkStreamVersion != -1;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.threading.async_scheduling."))
            return ThreadedHorizonsConfig.asyncSchedulingConfig.enabled;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.threading.lighting."))
            return !LoaderHooks.isModLoaded("lightbench");
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.mid_tick_chunk_tasks."))
            return ThreadedHorizonsConfig.generalOptimizationsConfig.doMidTickChunkTasks;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.chunkio."))
            return ThreadedHorizonsConfig.ioSystemConfig.replaceImpl;
        if (mixinClassName.equals("network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.MixinNbtCompound") ||
                mixinClassName.equals("network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.MixinNbtCompound1"))
            return !lithiumFamilyLoaded;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.disable_vanilla_mid_tick_autosave."))
            return mode != Mode.VANILLA;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave."))
            return mode == Mode.ENHANCED;
        if (mixinClassName.startsWith("network.vonix.threadedhorizons.mixin.optimization.worldgen.vanilla_optimization.aquifer."))
            return ThreadedHorizonsConfig.vanillaWorldGenOptimizationsConfig.optimizeAquifer;
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        LOGGER.info("TH_MIXIN_APPLIED {} -> {}", mixinClassName, targetClassName);
    }
}
