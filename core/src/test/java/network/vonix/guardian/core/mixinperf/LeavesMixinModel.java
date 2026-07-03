/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

/**
 * Pure-Java model of the LeavesBlockMixin decision surface. Used by the
 * v1.3.0 W1c regression tests + hot-path benchmark to prove that the
 * @Redirect-on-removeBlock pattern only submits when the tick actually
 * mutates the world (real decay), not on every leaves tick head.
 *
 * <p>The model captures both the OLD (HEAD-inject) discipline and the NEW
 * (@Redirect-on-removeBlock) discipline so a single test can drive both
 * against identical event streams and assert the reduction in submit count.
 */
public final class LeavesMixinModel {

    private LeavesMixinModel() {}

    /**
     * OLD HEAD-injection discipline: submit for every leaves tick where the
     * short-circuits (persistent / distance<7) don't fire. This is the
     * pre-W1c behavior we tightened.
     *
     * @return {@code true} if the old logic would submit
     */
    public static boolean oldHeadSubmit(boolean persistent, int distance) {
        if (persistent) return false;
        if (distance < 7) return false;
        return true; // fires on HEAD regardless of whether vanilla actually removed anything
    }

    /**
     * NEW @Redirect-on-removeBlock discipline (v1.3.0 W1c): submit only when
     * {@code ServerLevel.removeBlock} actually returned true AND we replaced
     * a non-air block. This tracks the real world mutation.
     *
     * @return {@code true} if the new logic would submit
     */
    public static boolean newRedirectSubmit(boolean removeBlockChanged, boolean oldStateWasNonAir) {
        return removeBlockChanged && oldStateWasNonAir;
    }
}
