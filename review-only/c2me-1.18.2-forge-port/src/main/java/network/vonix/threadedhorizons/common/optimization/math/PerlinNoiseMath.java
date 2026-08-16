package network.vonix.threadedhorizons.common.optimization.math;

/**
 * Official-mapping equivalent of the retained {@code PerlinNoise.wrap} overwrite.
 */
public final class PerlinNoiseMath {
    private PerlinNoiseMath() {
    }

    public static double wrap(double value) {
        return value - Math.floor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
    }
}
