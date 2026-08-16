package network.vonix.threadedhorizons.mixin.access;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiNoiseBiomeSource.class)
public interface IMultiNoiseBiomeSource {

    @Accessor("parameters")
    Climate.ParameterList<Holder<Biome>> getBiomeEntries();

}
