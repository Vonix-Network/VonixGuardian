package network.vonix.threadedhorizons.mixin.optimization.reduce_allocs.object_pooling_caching;

import network.vonix.threadedhorizons.common.optimization.reduce_allocs.ObjectCachingUtils;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.BitSet;

@Mixin(OreFeature.class)
public class MixinOreFeature {

    @Redirect(method = "doPlace", at = @At(value = "NEW", target = "java/util/BitSet"))
    private BitSet redirectNewBitSet(int nbits) {
        return ObjectCachingUtils.getCachedOrNewBitSet(nbits);
    }

}
