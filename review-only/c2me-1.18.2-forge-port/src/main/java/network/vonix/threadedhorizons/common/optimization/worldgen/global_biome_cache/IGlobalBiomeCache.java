package network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache;

import net.minecraft.core.SectionPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

public interface IGlobalBiomeCache {

    Holder<Biome>[][][] preloadBiomes(SectionPos pos, Holder<Biome>[][][] def, Climate.Sampler multiNoiseSampler);

    Holder<Biome> getBiomeForNoiseGenFast(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler);
}
