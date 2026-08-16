package network.vonix.threadedhorizons.common.threading.worldgen.debug;


import com.google.common.collect.Sets;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import com.mojang.logging.LogUtils;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import network.vonix.threadedhorizons.platform.MappingHooks;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

// Used to examine getChunk calls with reduced lock radius
public class StacktraceRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean doRecord = !Boolean.getBoolean("network.vonix.threadedhorizons.common.threading.worldgen.debug.NoDebugReducedLockRadius") && ThreadedHorizonsConfig.threadedWorldGenConfig.reduceLockRadius;
    private static final boolean warnAtWarningLevel = !Boolean.getBoolean("network.vonix.threadedhorizons.common.threading.worldgen.debug.DebugReducedLockRadiusAtWarningLevel");
    private static final int recordFrequency = Mth.clamp(Integer.getInteger("network.vonix.threadedhorizons.common.threading.worldgen.debug.DebugReducedLockRadiusFrequency", 4), 1, 16);
    private static final long frequencyBitMask = (1L << recordFrequency) - 1;

    private static final Set<StacktraceHolder> recordedStacktraces = Sets.newConcurrentHashSet();
    private static final AtomicLong sampledCount = new AtomicLong();

    public static void record() {
        if (!doRecord) return;
        if ((sampledCount.incrementAndGet() & frequencyBitMask) != 0) return;
        final StacktraceHolder stacktraceHolder = new StacktraceHolder();
        if (recordedStacktraces.add(stacktraceHolder)) {
            if (stacktraceHolder.needPrint()) {
                LOGGER.warn("Potential dangerous call with reducedLockRadius", stacktraceHolder.throwable);
            } else {
//                LOGGER.info("Ignoring safe call");
            }
        }
    }


    public static class StacktraceHolder {

        private static final String StructureProcessor$process = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor", "processBlock", "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;");
        private static final String BlendingData$getBlendingData = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.levelgen.blending.BlendingData", "getOrUpdateBlendingData", "(Lnet/minecraft/server/level/WorldGenRegion;II)Lnet/minecraft/world/level/levelgen/blending/BlendingData;");
        private static final String ChunkGenerator$carve = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.chunk.ChunkGenerator", "applyCarvers", "(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/StructureFeatureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)V");
        private static final String NaturalSpawner$populateEntities = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.NaturalSpawner", "spawnMobsForChunkGeneration", "(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/level/biome/Biome;Lnet/minecraft/world/level/ChunkPos;Ljava/util/Random;)V");
        private static final String StructureFeatureManager$method_41032 = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.StructureFeatureManager", "method_41032", "(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;)Ljava/util/List;");
        private static final String BiomeManager$Storage$getBiomeForNoiseGen = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.biome.BiomeManager$NoiseBiomeSource", "getNoiseBiome", "(III)Lnet/minecraft/core/Holder;");
        private static final String BlockCollisions$getChunk = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.BlockCollisions", "getChunk", "(II)Lnet/minecraft/world/level/BlockGetter;");

        @NotNull
        private final StackTraceElement[] stackTrace;
        private final Throwable throwable;

        public StacktraceHolder() {
            this.throwable = new Throwable();
            this.stackTrace = this.throwable.getStackTrace();
        }

        public boolean needPrint() {
            for (StackTraceElement stackTraceElement : stackTrace) {
                if (stackTraceElement.getMethodName().equals("method_26971"))
                    return false;
                if (stackTraceElement.getClassName().equals(RuleProcessor.class.getName()) &&
                        stackTraceElement.getMethodName().equals(StructureProcessor$process))
                    return false;
                if (stackTraceElement.getClassName().equals(BlendingData.class.getName()) &&
                        stackTraceElement.getMethodName().equals(BlendingData$getBlendingData))
                    return false;
                if (stackTraceElement.getClassName().equals(NoiseBasedChunkGenerator.class.getName()) &&
                        stackTraceElement.getMethodName().equals(ChunkGenerator$carve))
                    return false;
                if (stackTraceElement.getClassName().equals(NaturalSpawner.class.getName()) &&
                        stackTraceElement.getMethodName().equals(NaturalSpawner$populateEntities))
                    return false;
                if (stackTraceElement.getClassName().equals(StructureFeatureManager.class.getName()) &&
                        stackTraceElement.getMethodName().equals(StructureFeatureManager$method_41032))
                    return false;
                if (stackTraceElement.getClassName().equals(LevelReader.class.getName()) &&
                        stackTraceElement.getMethodName().equals(BiomeManager$Storage$getBiomeForNoiseGen))
                    return false;
                if (stackTraceElement.getClassName().equals(BlockCollisions.class.getName()) &&
                        stackTraceElement.getMethodName().equals(BlockCollisions$getChunk))
                    return false;

                // lithium
                if (stackTraceElement.getClassName().equals("me.jellysquid.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper"))
                    return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "StacktraceHolder{" +
                    "stackTrace=" + Arrays.toString(stackTrace) +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StacktraceHolder that = (StacktraceHolder) o;
            return Arrays.equals(stackTrace, that.stackTrace);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(stackTrace);
        }
    }

}
