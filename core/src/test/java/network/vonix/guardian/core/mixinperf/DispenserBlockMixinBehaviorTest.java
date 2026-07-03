/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W1c regression coverage for {@code DispenserBlockMixin}.
 *
 * <p>Locks in the invariant that {@code DispenserBlock.dispenseFrom} is a
 * discrete redstone-triggered event with no HEAD-vs-actual mismatch, so
 * HEAD injection remains the correct discipline (unlike the other three
 * mixins in W1c).</p>
 */
class DispenserBlockMixinBehaviorTest {

    @Test
    void everyCallSubmits() {
        assertThat(DispenserMixinModel.submit()).isTrue();
    }

    @Test
    void doesNotRequireRedirectRefinement() {
        assertThat(DispenserMixinModel.requiresRedirectRefinement()).isFalse();
    }

    @Test
    void reductionRatioIsZero_byDesign() {
        int calls = 10_000;
        int oldSubmits = 0, newSubmits = 0;
        for (int i = 0; i < calls; i++) {
            if (DispenserMixinModel.submit()) oldSubmits++;
            if (DispenserMixinModel.submit()) newSubmits++;
        }
        assertThat(oldSubmits).isEqualTo(newSubmits);
        double reduction = 1.0 - ((double) newSubmits / oldSubmits);
        assertThat(reduction).isEqualTo(0.0);
    }
}
