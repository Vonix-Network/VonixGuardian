/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W1c regression coverage for {@code ConcretePowderBlockMixin}.
 *
 * <p>Verifies the Redirect-on-setBlock discipline only submits FORM when
 * the setBlock actually changed the block class (a real solidification),
 * and asserts ≥95% submit reduction on realistic onLand traffic where
 * most falling-block landings do NOT touch water.</p>
 */
class ConcretePowderBlockMixinBehaviorTest {

    @Test
    void oldFiresEvenWhenNoSolidification() {
        assertThat(ConcretePowderMixinModel.oldHeadSubmit()).isTrue();
        assertThat(ConcretePowderMixinModel.newRedirectSubmit(false, true, true, true)).isFalse();
    }

    @Test
    void newFiresOnRealSolidification() {
        assertThat(ConcretePowderMixinModel.newRedirectSubmit(true, true, true, true)).isTrue();
    }

    @Test
    void newSkipsSameBlockSet() {
        assertThat(ConcretePowderMixinModel.newRedirectSubmit(true, true, true, false)).isFalse();
    }

    @Test
    void updateShapeSkipsNoChange() {
        assertThat(ConcretePowderMixinModel.newUpdateShapeSubmit(true, false)).isFalse();
    }

    @Test
    void updateShapeFiresOnNeighborInducedForm() {
        assertThat(ConcretePowderMixinModel.newUpdateShapeSubmit(true, true)).isTrue();
    }

    @Test
    void reductionOnRealisticLandingStream_meets95pct() {
        // Model: 10_000 concrete-powder onLand calls. Vast majority land on
        //   dry blocks (~98%); only ~2% land on/near water and solidify.
        SplittableRandom rnd = new SplittableRandom(0xC0DEL);
        int oldSubmits = 0, newSubmits = 0;
        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            boolean solidified = rnd.nextInt(50) == 0; // 2%
            if (ConcretePowderMixinModel.oldHeadSubmit()) oldSubmits++;
            if (ConcretePowderMixinModel.newRedirectSubmit(solidified, true, true, solidified)) newSubmits++;
        }
        assertThat(oldSubmits).isEqualTo(iterations);
        assertThat(newSubmits).isGreaterThan(0);
        double reduction = 1.0 - ((double) newSubmits / oldSubmits);
        assertThat(reduction)
                .as("W1c concrete-powder submit-count reduction (old=%d new=%d)", oldSubmits, newSubmits)
                .isGreaterThanOrEqualTo(0.95);
    }
}
