package network.vonix.threadedhorizons.mixin.optimization.worldgen.thread_local_biome_cache;

import network.vonix.threadedhorizons.common.optimization.worldgen.threadlocal_biome_cache.ThreadLocalCachingMultiNoiseBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

@Mixin(MultiNoiseBiomeSource.Preset.class)
public class MixinMultiNoiseBiomeSourcePreset {

    @Dynamic
    @Redirect(method = "biomeSource(Lnet/minecraft/world/level/biome/MultiNoiseBiomeSource$PresetInstance;Z)Lnet/minecraft/world/level/biome/MultiNoiseBiomeSource;", at = @At(value = "NEW", target = "net/minecraft/world/level/biome/MultiNoiseBiomeSource"))
    private static MultiNoiseBiomeSource redirectConstruct(Climate.ParameterList<Holder<Biome>> entries, @SuppressWarnings("OptionalUsedAsFieldOrParameterType") Optional<MultiNoiseBiomeSource.PresetInstance> optional) {
        return new ThreadLocalCachingMultiNoiseBiomeSource(entries, optional);
    }

}
