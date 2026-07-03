/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

/**
 * Pure-Java model of the ConcretePowderBlockMixin decision surface for v1.3.0 W1c.
 * The previous HEAD-on-onLand injection submitted on every landing (regardless
 * of whether solidification actually happened); the new @Redirect-on-setBlock
 * pattern only submits when {@code Level.setBlock} truly changed the block class.
 */
public final class ConcretePowderMixinModel {

    private ConcretePowderMixinModel() {}

    /** OLD: submit on every ConcretePowderBlock.onLand HEAD call. */
    public static boolean oldHeadSubmit() {
        return true;
    }

    /**
     * NEW W1c: submit only when the setBlock actually succeeded AND the
     * new block's class differs from the old block's class (i.e. a real
     * solidification, not a no-op reset).
     */
    public static boolean newRedirectSubmit(boolean setChanged, boolean oldNonNull, boolean newNonNull, boolean blockClassChanged) {
        return setChanged && oldNonNull && newNonNull && blockClassChanged;
    }

    /**
     * updateShape @Inject("RETURN") post-guard: only submit when the returned
     * BlockState differs in block class from the input state.
     */
    public static boolean newUpdateShapeSubmit(boolean stateAndResultNonNull, boolean resultBlockClassDiffers) {
        return stateAndResultNonNull && resultBlockClassDiffers;
    }
}
