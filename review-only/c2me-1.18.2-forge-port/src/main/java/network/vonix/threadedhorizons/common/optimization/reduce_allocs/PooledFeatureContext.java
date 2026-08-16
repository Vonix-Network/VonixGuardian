package network.vonix.threadedhorizons.common.optimization.reduce_allocs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.Optional;
import java.util.Random;

public class PooledFeatureContext<FC extends FeatureConfiguration> extends FeaturePlaceContext<FC> {

    public static final ThreadLocal<SimpleObjectPool<PooledFeatureContext<?>>> POOL = ThreadLocal.withInitial(() -> new SimpleObjectPool<>(unused -> new PooledFeatureContext<>(), unused -> {}, 2048));

    private Optional<ConfiguredFeature<?, ?>> feature;
    private WorldGenLevel world;
    private ChunkGenerator generator;
    private Random random;
    private BlockPos origin;
    private FC config;

    public PooledFeatureContext() {
        super(null, null, null, null, null, null);
    }

    public void reInit(Optional<ConfiguredFeature<?, ?>> feature, WorldGenLevel world, ChunkGenerator generator, Random random, BlockPos origin, FC config) {
        this.feature = feature;
        this.world = world;
        this.generator = generator;
        this.random = random;
        this.origin = origin;
        this.config = config;
    }

    public void reInit() {
        this.feature = null;
        this.world = null;
        this.generator = null;
        this.random = null;
        this.origin = null;
        this.config = null;
    }

    @Override
    public WorldGenLevel level() {
        return this.world;
    }

    @Override
    public ChunkGenerator chunkGenerator() {
        return this.generator;
    }

    @Override
    public Random random() {
        return this.random;
    }

    @Override
    public BlockPos origin() {
        return this.origin;
    }

    @Override
    public FC config() {
        return this.config;
    }

    @Override
    public Optional<ConfiguredFeature<?, ?>> topFeature() {
        return this.feature;
    }
}
