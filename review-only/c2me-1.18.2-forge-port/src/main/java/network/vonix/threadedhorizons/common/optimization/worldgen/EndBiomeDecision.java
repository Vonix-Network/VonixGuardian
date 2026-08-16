package network.vonix.threadedhorizons.common.optimization.worldgen;

/**
 * Official 40.3.11 {@code TheEndBiomeSource.getNoiseBiome} decision function
 * (javap of the mapped class). The cache mixin must return the same branch.
 */
public final class EndBiomeDecision {
    public enum Kind {
        END,
        HIGHLANDS,
        MIDLANDS,
        ISLANDS,
        BARRENS
    }

    private EndBiomeDecision() {
    }

    public static Kind classify(int biomeX, int biomeZ, float heightValue) {
        int i = biomeX >> 2;
        int j = biomeZ >> 2;
        if ((long) i * (long) i + (long) j * (long) j <= 4096L) {
            return Kind.END;
        }
        if (heightValue > 40.0F) {
            return Kind.HIGHLANDS;
        }
        if (heightValue >= 0.0F) {
            return Kind.MIDLANDS;
        }
        return heightValue < -20.0F ? Kind.ISLANDS : Kind.BARRENS;
    }
}
