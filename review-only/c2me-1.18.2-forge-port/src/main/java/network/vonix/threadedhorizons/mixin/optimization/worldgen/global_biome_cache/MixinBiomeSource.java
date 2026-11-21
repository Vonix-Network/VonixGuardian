package network.vonix.threadedhorizons.mixin.optimization.worldgen.global_biome_cache;

import network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache.IGlobalBiomeCache;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BiomeSource.class)
public class MixinBiomeSource {

    @Redirect(method = {"getBiomesWithin", "findBiomeHorizontal"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/BiomeSource;getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> redirectGetBiomeForNoiseGen(BiomeSource biomeSource, int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler) {
        if (biomeSource instanceof IGlobalBiomeCache globalBiomeCache) {
            return globalBiomeCache.getBiomeForNoiseGenFast(biomeX, biomeY, biomeZ, multiNoiseSampler);
        }
        return biomeSource.getNoiseBiome(biomeX, biomeY, biomeZ, multiNoiseSampler);
    }

}
