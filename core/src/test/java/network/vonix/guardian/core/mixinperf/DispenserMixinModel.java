/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

/**
 * Pure-Java model of the DispenserBlockMixin decision surface for v1.3.0 W1c.
 *
 * <p>Unlike Leaves/Ice/ConcretePowder, {@code DispenserBlock.dispenseFrom}
 * is a discrete redstone-triggered event: it is only called when a real
 * dispense fires. HEAD injection here has NO over-submission — every call
 * corresponds to exactly one real dispense action. This model is included
 * to lock that invariant into a regression test so future refactors don't
 * accidentally introduce a HEAD-called-on-no-op path.</p>
 */
public final class DispenserMixinModel {

    private DispenserMixinModel() {}

    /** OLD == NEW for dispenser. Always submits when method is called. */
    public static boolean submit() {
        return true;
    }

    /**
     * Verification helper: reports whether a discrete-event mixin should use
     * @Redirect refinement. Dispenser's answer is {@code false} because
     * there is no fast-path early-return inside {@code dispenseFrom} that
     * would cause a HEAD injection to over-submit.
     */
    public static boolean requiresRedirectRefinement() {
        return false;
    }
}
