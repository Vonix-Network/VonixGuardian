package network.vonix.threadedhorizons.mixin.optimization.worldgen.vanilla_optimization.the_end_biome_cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import network.vonix.threadedhorizons.common.optimization.worldgen.EndBiomeDecision;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TheEndBiomeSource.class)
public abstract class MixinTheEndBiomeSource {

    @Shadow
    public static float getHeightValue(SimplexNoise simplexNoiseSampler, int i, int j) {
        return 0;
    }

    @Shadow @Final private SimplexNoise islandNoise;

    @Shadow @Final private Holder<Biome> highlands;

    @Shadow @Final private Holder<Biome> midlands;

    @Shadow @Final private Holder<Biome> islands;

    @Shadow @Final private Holder<Biome> barrens;

    @Shadow @Final private Holder<Biome> end;

    private Holder<Biome> getBiomeForNoiseGenVanilla(int biomeX, int biomeY, int biomeZ) {
        int i = biomeX >> 2;
        int j = biomeZ >> 2;
        float height = (long) i * (long) i + (long) j * (long) j <= 4096L
                ? 0.0F
                : getHeightValue(this.islandNoise, i * 2 + 1, j * 2 + 1);
        EndBiomeDecision.Kind kind = EndBiomeDecision.classify(biomeX, biomeZ, height);
        if (kind == EndBiomeDecision.Kind.END) {
            return this.end;
        }
        if (kind == EndBiomeDecision.Kind.HIGHLANDS) {
            return this.highlands;
        }
        if (kind == EndBiomeDecision.Kind.MIDLANDS) {
            return this.midlands;
        }
        if (kind == EndBiomeDecision.Kind.ISLANDS) {
            return this.islands;
        }
        return this.barrens;
    }

    private final ThreadLocal<Long2ObjectLinkedOpenHashMap<Holder<Biome>>> cache = ThreadLocal.withInitial(Long2ObjectLinkedOpenHashMap::new);
    private final int cacheCapacity = 1024;

    /**
     * @author ishland
     * @reason the end biome cache
     */
    @Overwrite
    public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler) {
        final long key = ChunkPos.asLong(biomeX, biomeZ);
        final Long2ObjectLinkedOpenHashMap<Holder<Biome>> cacheThreadLocal = cache.get();
        final Holder<Biome> biome = cacheThreadLocal.get(key);
        if (biome != null) {
            return biome;
        } else {
            final Holder<Biome> gennedBiome = getBiomeForNoiseGenVanilla(biomeX, biomeY, biomeZ);
            cacheThreadLocal.put(key, gennedBiome);
            if (cacheThreadLocal.size() > cacheCapacity) {
                for (int i = 0; i < cacheCapacity / 16; i ++) {
                    cacheThreadLocal.removeFirst();
                }
            }
            return gennedBiome;
        }
    }

}
