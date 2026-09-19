package network.vonix.threadedhorizons.mixin.optimization.math;

import network.vonix.threadedhorizons.common.optimization.math.ImprovedNoiseMath;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ImprovedNoise.class, priority = 1090)
public abstract class MixinPerlinNoiseSampler {

    @Shadow @Final public double yo;

    @Shadow @Final public double xo;

    @Shadow @Final public double zo;

    @Shadow @Final private byte[] p;

    /**
     * @author ishland
     * @reason optimize: remove frequent type conversions
     */
    @Deprecated
    @Overwrite
    public double noise(double x, double y, double z, double yScale, double yMax) {
        return ImprovedNoiseMath.noise(this.xo, this.yo, this.zo, this.p, x, y, z, yScale, yMax);
    }

    /**
     * @author ishland
     * @reason inline math & small optimization: remove frequent type conversions and redundant ops
     */
    @Overwrite
    private double sampleAndLerp(int sectionX, int sectionY, int sectionZ, double localX, double localY, double localZ, double fadeLocalX) {
        return ImprovedNoiseMath.sampleAndLerp(this.p, sectionX, sectionY, sectionZ, localX, localY, localZ, fadeLocalX);
    }

}
