/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit shim so {@link OtherMixinsHotPathBench} runs on every core test
 * pass and enforces the v1.3.0 W1c reduction targets.
 */
class OtherMixinsHotPathBenchTest {

    @Test
    void leavesReductionMeetsTarget() {
        OtherMixinsHotPathBench.Result r = OtherMixinsHotPathBench.benchLeaves();
        assertThat(r.reductionPct())
                .as("Leaves W1c submit-reduction (old=%d new=%d)", r.oldSubmits, r.newSubmits)
                .isGreaterThanOrEqualTo(70.0);
    }

    @Test
    void iceReductionMeetsTarget() {
        OtherMixinsHotPathBench.Result r = OtherMixinsHotPathBench.benchIce();
        assertThat(r.reductionPct())
                .as("Ice W1c submit-reduction (old=%d new=%d)", r.oldSubmits, r.newSubmits)
                .isGreaterThanOrEqualTo(90.0);
    }

    @Test
    void concretePowderReductionMeetsTarget() {
        OtherMixinsHotPathBench.Result r = OtherMixinsHotPathBench.benchConcrete();
        assertThat(r.reductionPct())
                .as("ConcretePowder W1c submit-reduction (old=%d new=%d)", r.oldSubmits, r.newSubmits)
                .isGreaterThanOrEqualTo(95.0);
    }
}
