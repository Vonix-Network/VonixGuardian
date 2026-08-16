package network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache;

import network.vonix.threadedhorizons.common.util.ListIndexedIterable;
import network.vonix.threadedhorizons.mixin.access.IMultiNoiseBiomeSource;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.List;
import java.util.Optional;

public class GlobalCachingMultiNoiseBiomeSource extends MultiNoiseBiomeSource implements IGlobalBiomeCache {

    private volatile MultiBiomeCache multiBiomeCache = null;

    public GlobalCachingMultiNoiseBiomeSource(Climate.ParameterList<Holder<Biome>> entries) {
        super(entries);
        initCache();
    }

    public GlobalCachingMultiNoiseBiomeSource(Climate.ParameterList<Holder<Biome>> entries, @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<MultiNoiseBiomeSource.PresetInstance> optional) {
        super(entries, optional);
        initCache();
    }

    private void initCache() {
        final List<Holder<Biome>> biomes = ((IMultiNoiseBiomeSource) this).getBiomeEntries().values().stream()
                .map(Pair::getSecond).toList();
        this.multiBiomeCache = new MultiBiomeCache(super::getNoiseBiome, new ListIndexedIterable<>(biomes));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler) {
        if (this.multiBiomeCache != null && !multiNoiseSampler.getClass().isSynthetic() && !multiNoiseSampler.getClass().isHidden()) {
            return this.multiBiomeCache.getBiomeForNoiseGen(biomeX, biomeY, biomeZ, multiNoiseSampler, false);
        } else {
            return super.getNoiseBiome(biomeX, biomeY, biomeZ, multiNoiseSampler);
        }
    }

    @Override
    public Holder<Biome> getBiomeForNoiseGenFast(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler) {
        if (this.multiBiomeCache != null && !multiNoiseSampler.getClass().isSynthetic() && !multiNoiseSampler.getClass().isHidden()) {
            return this.multiBiomeCache.getBiomeForNoiseGen(biomeX, biomeY, biomeZ, multiNoiseSampler, true);
        } else {
            return super.getNoiseBiome(biomeX, biomeY, biomeZ, multiNoiseSampler);
        }
    }

    @Override
    public Holder<Biome>[][][] preloadBiomes(SectionPos pos, Holder<Biome>[][][] def, Climate.Sampler multiNoiseSampler) {
        if (this.multiBiomeCache != null) {
            return this.multiBiomeCache.preloadBiomes(pos, def, multiNoiseSampler);
        } else {
            throw new IllegalStateException("Not initialized");
        }
    }
}
