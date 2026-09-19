package network.vonix.threadedhorizons.mixin.access;

import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SingleThreadedRandomSource.class)
public interface ISimpleRandom {

    @Accessor
    long getSeed();

    @Accessor
    void setSeed(long seed);

}
