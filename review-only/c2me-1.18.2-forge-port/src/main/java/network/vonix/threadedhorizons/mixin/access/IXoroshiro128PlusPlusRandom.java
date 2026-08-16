package network.vonix.threadedhorizons.mixin.access;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(XoroshiroRandomSource.class)
public interface IXoroshiro128PlusPlusRandom {

    @Accessor("randomNumberGenerator")
    Xoroshiro128PlusPlus getImplementation();

}
