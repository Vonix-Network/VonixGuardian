package network.vonix.threadedhorizons.mixin.optimization.worldgen.random_instances;

import network.vonix.threadedhorizons.common.optimization.worldgen.random_instances.SimplifiedAtomicSimpleRandom;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = {
        NoiseBasedChunkGenerator.class,
        GeodeFeature.class,
})
public class MixinRedirectAtomicSimpleRandom {

    @Redirect(method = "*", at = @At(value = "NEW", target = "net/minecraft/world/level/levelgen/LegacyRandomSource"))
    private LegacyRandomSource redirectAtomicSimpleRandom(long l) {
        return new SimplifiedAtomicSimpleRandom(l);
    }

}
