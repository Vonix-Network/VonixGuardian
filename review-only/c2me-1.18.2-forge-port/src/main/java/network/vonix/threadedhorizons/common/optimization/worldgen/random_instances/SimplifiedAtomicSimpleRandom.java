package network.vonix.threadedhorizons.common.optimization.worldgen.random_instances;

import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import net.minecraft.world.level.levelgen.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

public class SimplifiedAtomicSimpleRandom extends LegacyRandomSource {
    private static final int INT_BITS = 48;
    private static final long SEED_MASK = 281474976710655L;
    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;
    private long seed;
    private final MarsagliaPolarGaussian gaussianSource = new MarsagliaPolarGaussian(this);

    public SimplifiedAtomicSimpleRandom(long seed) {
        super(0);
        // super() virtual-calls setSeed before this.gaussianSource exists
        this.seed = (seed ^ MULTIPLIER) & SEED_MASK;
    }

    @Override
    public RandomSource fork() {
        return new SingleThreadedRandomSource(this.nextLong());
    }

    @Override
    public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
        return new LegacyRandomSource.LegacyPositionalRandomFactory(this.nextLong());
    }

    @Override
    public void setSeed(long l) {
        this.seed = (l ^ MULTIPLIER) & SEED_MASK;
        if (this.gaussianSource != null) {
            this.gaussianSource.reset();
        }
    }

    @Override
    public int next(int bits) {
        long l = this.seed * MULTIPLIER + INCREMENT & SEED_MASK;
        this.seed = l;
        return (int)(l >> INT_BITS - bits);
    }

    @Override
    public double nextGaussian() {
        return this.gaussianSource.nextGaussian();
    }
}
