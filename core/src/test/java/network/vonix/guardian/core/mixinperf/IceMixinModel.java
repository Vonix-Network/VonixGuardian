/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

/**
 * Pure-Java model of the IceBlockMixin decision surface for v1.3.0 W1c.
 * Same discipline as LeavesMixinModel — the previous HEAD-on-melt injection
 * submitted whether or not the vanilla method actually mutated the block;
 * the new @Redirect-on-removeBlock+setBlockAndUpdate pattern only submits
 * when a real ice→water conversion took place.
 */
public final class IceMixinModel {

    private IceMixinModel() {}

    /** OLD: submit on every {@code IceBlock.melt} head call, no gate. */
    public static boolean oldHeadSubmit() {
        return true;
    }

    /**
     * NEW W1c: submit only when the redirected mutation actually changed the
     * world (removeBlock returned true, or setBlockAndUpdate returned true and
     * the block class actually changed).
     */
    public static boolean newRedirectSubmitRemove(boolean removeBlockChanged, boolean oldStateWasNonAir) {
        return removeBlockChanged && oldStateWasNonAir;
    }

    /**
     * NEW W1c setBlockAndUpdate variant: extra guard that the target block
     * class changed (avoids double-submitting when melt sets ice→ice, which
     * doesn't happen vanilla but does in mods that reroute the mixin target).
     */
    public static boolean newRedirectSubmitSet(boolean setChanged, boolean oldStateWasNonAir, boolean blockClassChanged) {
        return setChanged && oldStateWasNonAir && blockClassChanged;
    }
}
