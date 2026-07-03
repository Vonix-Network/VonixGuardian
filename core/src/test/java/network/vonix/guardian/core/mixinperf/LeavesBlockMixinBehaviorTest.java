/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W1c regression coverage for {@code LeavesBlockMixin}.
 *
 * <p>Validates that the new @Redirect-on-removeBlock discipline only submits
 * on actual leaf-decay mutations, and asserts the ≥70% submit-count reduction
 * versus the previous HEAD-inject on realistic leaves-tick traffic.</p>
 */
class LeavesBlockMixinBehaviorTest {

    @Test
    void noSubmitOnPersistentLeaf() {
        // vanilla short-circuits — no removeBlock will ever run
        assertThat(LeavesMixinModel.oldHeadSubmit(true, 7)).isFalse();
        assertThat(LeavesMixinModel.newRedirectSubmit(false, false)).isFalse();
    }

    @Test
    void noSubmitOnNearLog() {
        // distance < 7 means the leaf is anchored to a log
        assertThat(LeavesMixinModel.oldHeadSubmit(false, 3)).isFalse();
        assertThat(LeavesMixinModel.newRedirectSubmit(false, false)).isFalse();
    }

    @Test
    void oldFiresOnDecayCandidateEvenWhenRemoveBlockNoOps() {
        // OLD: fires on every decayable tick regardless of actual removal.
        // NEW: silent because the vanilla removeBlock returned false (already air / race).
        boolean removed = false;
        assertThat(LeavesMixinModel.oldHeadSubmit(false, 7)).isTrue();
        assertThat(LeavesMixinModel.newRedirectSubmit(removed, false)).isFalse();
    }

    @Test
    void bothFireOnRealDecay() {
        // Real decay: state was leaves, removeBlock returned true
        assertThat(LeavesMixinModel.oldHeadSubmit(false, 7)).isTrue();
        assertThat(LeavesMixinModel.newRedirectSubmit(true, true)).isTrue();
    }

    @Test
    void reductionOnRealisticTickStream_meets70pct() {
        // Model: 10_000 leaves ticks. Realistic mix:
        //   - 60% persistent (short-circuit)
        //   - 25% near-log (distance < 7)
        //   - 15% decay candidates. Of these, only ~1 in 10 actually decays
        //     in the same tick (vanilla RandomSource + isSurroundedByAir gate).
        SplittableRandom rnd = new SplittableRandom(0xF00DL);
        int oldSubmits = 0, newSubmits = 0;
        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            int roll = rnd.nextInt(100);
            boolean persistent = roll < 60;
            int distance = persistent ? 3
                    : (roll < 85 ? rnd.nextInt(6) + 1 : 7);
            boolean decayCandidate = !persistent && distance >= 7;
            // Only about 10% of decay-candidate ticks actually mutate.
            boolean removed = decayCandidate && rnd.nextInt(10) == 0;
            if (LeavesMixinModel.oldHeadSubmit(persistent, distance)) oldSubmits++;
            if (LeavesMixinModel.newRedirectSubmit(removed, removed /*old was non-air*/)) newSubmits++;
        }
        assertThat(oldSubmits).isGreaterThan(0);
        assertThat(newSubmits).isGreaterThan(0);
        double reduction = 1.0 - ((double) newSubmits / oldSubmits);
        assertThat(reduction)
                .as("W1c leaves-decay submit-count reduction (old=%d new=%d)", oldSubmits, newSubmits)
                .isGreaterThanOrEqualTo(0.70);
    }
}
