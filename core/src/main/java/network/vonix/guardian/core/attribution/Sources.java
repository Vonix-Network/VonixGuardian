/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import network.vonix.guardian.core.event.Sentinel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Enumeration-style constants for non-player attribution sources that show up
 * in the {@code sourceTag} column and drive the {@code /vg lookup src:&lt;name&gt;}
 * filter (planned X4/X5 work).
 *
 * <p>Distinct from {@link Sentinel} — sentinels are the actor-name string
 * assigned to a row when no player UUID exists; {@code Sources} names are
 * the classifier put in {@code sourceTag} to group rows produced by the same
 * event category regardless of which specific sentinel was picked.</p>
 *
 * <p>v1.3.1 X3 introduces the {@link #FLUID} entry to classify rows produced
 * by the {@code LiquidBlockMixin} pipeline (water/lava spread). When the
 * {@link FluidSourceMemory} 2-min traceback resolves a bucket-empty ancestor
 * within the flow radius, the row is still tagged with {@link #FLUID} but
 * carries the emptying player's UUID + name.</p>
 *
 * @since 1.3.1 (X3)
 */
public final class Sources {

    private Sources() {}

    /**
     * Water/lava flow producer. sourceTag values will be of the form
     * {@code "#fluid:water"} or {@code "#fluid:lava"} so the mixin-hot-events
     * kill-switch (see {@code MixinHotEventFilter}) can match on the
     * {@link Sentinel#FLUID} prefix.
     */
    public static final String FLUID = Sentinel.FLUID;

    /** Unmodifiable view of every source constant in declaration order. */
    public static final Set<String> ALL;

    static {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add(FLUID);
        ALL = Collections.unmodifiableSet(s);
    }

    /**
     * @param name candidate sourceTag classifier prefix (may be {@code null})
     * @return {@code true} iff {@code name} matches one of the recognised
     *         non-player source classifiers
     */
    public static boolean isKnown(String name) {
        return name != null && ALL.contains(name);
    }
}
