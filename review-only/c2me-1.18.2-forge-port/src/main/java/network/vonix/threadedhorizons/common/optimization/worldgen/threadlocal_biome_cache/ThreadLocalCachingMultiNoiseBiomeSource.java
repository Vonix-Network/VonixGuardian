package network.vonix.threadedhorizons.common.optimization.worldgen.threadlocal_biome_cache;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Optional;

public class ThreadLocalCachingMultiNoiseBiomeSource extends MultiNoiseBiomeSource {

    private final BiomeSourceCachingDelegate biomeSourceCachingDelegate = new BiomeSourceCachingDelegate(super::getNoiseBiome, this);

    public ThreadLocalCachingMultiNoiseBiomeSource(Climate.ParameterList<Holder<Biome>> entries) {
        super(entries);
    }

    public ThreadLocalCachingMultiNoiseBiomeSource(Climate.ParameterList<Holder<Biome>> entries, @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<MultiNoiseBiomeSource.PresetInstance> optional) {
        super(entries, optional);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler) {
        return this.biomeSourceCachingDelegate.getNoiseBiome(biomeX, biomeY, biomeZ, multiNoiseSampler);
    }
}
