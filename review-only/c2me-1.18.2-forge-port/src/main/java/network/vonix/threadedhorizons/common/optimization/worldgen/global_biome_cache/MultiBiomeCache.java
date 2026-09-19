package network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import network.vonix.threadedhorizons.common.optimization.worldgen.threadlocal_biome_cache.BiomeSourceCachingDelegate;
import com.mojang.datafixers.util.Function4;
import net.minecraft.core.IdMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.UnfairExecutor;

import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class MultiBiomeCache {

    public static final UnfairExecutor EXECUTOR = new UnfairExecutor(2, new ThreadFactoryBuilder().setNameFormat("Threaded Horizons biomes #%d").setDaemon(true).setPriority(Thread.NORM_PRIORITY - 1).build());
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Biome Cache");

    private final IdMap<Holder<Biome>> registry;

    private final Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate;
    private final BiomeSourceCachingDelegate biomeSourceCachingDelegate;

    public MultiBiomeCache(Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate, IdMap<Holder<Biome>> registry) {
        this.registry = registry;
        this.delegate = delegate;
        this.biomeSourceCachingDelegate = new BiomeSourceCachingDelegate(this.delegate);
    }

    private final ConcurrentHashMap<Climate.Sampler, LoadingCache<SectionPos, Holder<Biome>[][][]>> biomeCaches = new ConcurrentHashMap<>();

    private final ThreadLocal<WeakHashMap<Climate.Sampler, WeakHashMap<SectionPos, Holder<Biome>[][][]>>> threadLocalCache = ThreadLocal.withInitial(WeakHashMap::new);

    private LoadingCache<SectionPos, Holder<Biome>[][][]> createCache(Climate.Sampler multiNoiseSampler) {
        Preconditions.checkNotNull(multiNoiseSampler);
        return CacheBuilder.newBuilder()
                .softValues()
                .maximumSize(8192)
                .build(new CacheLoader<>() {
                    @Override
                    public Holder<Biome>[][][] load(SectionPos key) {
                        int startX = QuartPos.fromBlock(key.minBlockX());
                        int startY = QuartPos.fromBlock(key.minBlockY());
                        int startZ = QuartPos.fromBlock(key.minBlockZ());
                        final Holder<Biome>[][][] result = new Holder[4][4][4];
                        for (int x = startX; x < startX + 4; x++) {
                            for (int y = startY; y < startY + 4; y++) {
                                for (int z = startZ; z < startZ + 4; z++) {
                                    result[x - startX][y - startY][z - startZ] = delegate.apply(x, y, z, multiNoiseSampler);
                                    Preconditions.checkNotNull(result[x - startX][y - startY][z - startZ]);
                                }
                            }
                        }
                        return result;
                    }
                });
    }

    private LoadingCache<SectionPos, Holder<Biome>[][][]> getCache(Climate.Sampler multiNoiseSampler) {
        return this.biomeCaches.computeIfAbsent(multiNoiseSampler, this::createCache);
    }

    private Holder[][][] getCachedBiome(SectionPos chunkSectionPos, Climate.Sampler multiNoiseSampler) {
        return this.threadLocalCache.get()
                .computeIfAbsent(multiNoiseSampler, unused -> new WeakHashMap<>())
                .computeIfAbsent(chunkSectionPos, getCache(multiNoiseSampler));
    }

    public Holder<Biome> getBiomeForNoiseGen(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler, boolean fast) {
        final SectionPos chunkPos = SectionPos.of(QuartPos.toSection(biomeX), QuartPos.toSection(biomeY), QuartPos.toSection(biomeZ));
        final int offsetX = biomeX - QuartPos.fromBlock(chunkPos.minBlockX());
        final int offsetY = biomeY - QuartPos.fromBlock(chunkPos.minBlockY());
        final int offsetZ = biomeZ - QuartPos.fromBlock(chunkPos.minBlockZ());
        return getCachedBiome(chunkPos, multiNoiseSampler)[offsetX][offsetY][offsetZ];
    }

    public Holder<Biome>[][][] preloadBiomes(SectionPos pos, Holder<Biome>[][][] def, Climate.Sampler multiNoiseSampler) {
        if (def != null) {
            getCache(multiNoiseSampler).put(pos, def);
            return def;
        } else {
            return getCachedBiome(pos, multiNoiseSampler);
        }
    }

    public interface BiomeProvider {
        Biome sample(Registry<Biome> biomeRegistry, int x, int y, int z);
    }

}
