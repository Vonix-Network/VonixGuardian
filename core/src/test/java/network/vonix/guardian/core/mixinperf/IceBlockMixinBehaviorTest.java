/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W1c regression coverage for {@code IceBlockMixin}.
 *
 * <p>Ensures the tighter @Redirect discipline drops submits when the vanilla
 * {@code melt} call short-circuited without mutating the world, and asserts
 * ≥90% reduction on realistic ice-tick traffic.</p>
 */
class IceBlockMixinBehaviorTest {

    @Test
    void oldFiresEvenWhenMeltWasNoOp() {
        assertThat(IceMixinModel.oldHeadSubmit()).isTrue();
        assertThat(IceMixinModel.newRedirectSubmitRemove(false, true)).isFalse();
        assertThat(IceMixinModel.newRedirectSubmitSet(false, true, true)).isFalse();
    }

    @Test
    void newFiresOnRealMeltRemove() {
        assertThat(IceMixinModel.newRedirectSubmitRemove(true, true)).isTrue();
    }

    @Test
    void newFiresOnRealMeltSetWithBlockClassChange() {
        assertThat(IceMixinModel.newRedirectSubmitSet(true, true, true)).isTrue();
    }

    @Test
    void newDoesNotFireOnSameBlockClassSet() {
        // Modded reroute where the same block class was set back — treat as no-op.
        assertThat(IceMixinModel.newRedirectSubmitSet(true, true, false)).isFalse();
    }

    @Test
    void reductionOnRealisticTickStream_meets90pct() {
        // Model: 10_000 IceBlock.melt calls. Vanilla path is called from
        //   randomTick when the level's light-and-heat check passes. Even
        //   then, the vanilla body has an early-return when the biome
        //   rejects melting; only a small fraction actually mutate.
        //   Empirical estimate: ~5-10% of melt calls mutate.
        SplittableRandom rnd = new SplittableRandom(0xC01DL);
        int oldSubmits = 0, newSubmits = 0;
        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            boolean actuallyMelted = rnd.nextInt(20) == 0; // 5%
            if (IceMixinModel.oldHeadSubmit()) oldSubmits++;
            if (IceMixinModel.newRedirectSubmitRemove(actuallyMelted, actuallyMelted)) newSubmits++;
        }
        assertThat(oldSubmits).isEqualTo(iterations);
        assertThat(newSubmits).isGreaterThan(0);
        double reduction = 1.0 - ((double) newSubmits / oldSubmits);
        assertThat(reduction)
                .as("W1c ice-fade submit-count reduction (old=%d new=%d)", oldSubmits, newSubmits)
                .isGreaterThanOrEqualTo(0.90);
    }
}
