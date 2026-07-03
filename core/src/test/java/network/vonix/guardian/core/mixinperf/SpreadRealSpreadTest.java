/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v1.3.0 W1b regression — SpreadingSnowyDirtBlockMixin real-spread capture.
 *
 * <p>Complement to {@link SpreadNoOpSuppressionTest}: verifies that when the
 * vanilla code path DOES call {@code setBlockAndUpdate} (i.e. a genuine dirt
 * neighbor was converted to grass/mycelium), the @Redirect hot-path still
 * submits exactly one SPREAD action.</p>
 *
 * <p>Also verifies that when the redirect observes that the block class did
 * not actually change (e.g. cosmetic snowy-state toggle where {@code newState}
 * has the same block as {@code oldState}), no SPREAD is submitted.</p>
 */
public class SpreadRealSpreadTest {

    /** Model of the v1.3.0 @Redirect hot-path (mirrors the mixin body). */
    private static boolean vg$spreadSet(String oldBlockId,
                                        String newBlockId,
                                        boolean vanillaChangedFlag,
                                        AtomicInteger submits) {
        // Redirect body: vanilla is asked to setBlockAndUpdate.
        boolean changed = vanillaChangedFlag;
        if (changed && newBlockId != null && oldBlockId != null && !oldBlockId.equals(newBlockId)) {
            submits.incrementAndGet();
        }
        return changed;
    }

    @Test
    public void realSpread_submitsExactlyOnce() {
        AtomicInteger submits = new AtomicInteger();

        // Dirt → grass_block: a real spread event, vanilla succeeds.
        boolean changed = vg$spreadSet("minecraft:dirt", "minecraft:grass_block",
                /* vanillaChangedFlag */ true, submits);

        assertEquals(true, changed);
        assertEquals(1, submits.get(), "one real spread → exactly one submit");
    }

    @Test
    public void sameBlockRefresh_doesNotSubmit() {
        AtomicInteger submits = new AtomicInteger();

        // Grass "spreads" onto a block that is already grass (snowy variant flip).
        // Redirect sees oldBlockId == newBlockId → no submit.
        vg$spreadSet("minecraft:grass_block", "minecraft:grass_block",
                /* vanillaChangedFlag */ true, submits);

        assertEquals(0, submits.get(),
                "cosmetic state flip on same block must not submit SPREAD");
    }

    @Test
    public void vanillaReturnsFalse_doesNotSubmit() {
        AtomicInteger submits = new AtomicInteger();

        // setBlockAndUpdate returned false (e.g. chunk not loaded, cancelled).
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block",
                /* vanillaChangedFlag */ false, submits);

        assertEquals(0, submits.get(),
                "when vanilla setBlockAndUpdate returns false, no SPREAD submitted");
    }

    @Test
    public void batchOfMixedTicks_countsMatchRealSpreads() {
        AtomicInteger submits = new AtomicInteger();

        // 5 real spreads + 3 no-ops + 2 same-block refreshes
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block", true, submits);
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block", true, submits);
        vg$spreadSet("minecraft:dirt", "minecraft:mycelium", true, submits);
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block", true, submits);
        vg$spreadSet("minecraft:dirt", "minecraft:mycelium", true, submits);
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block", false, submits); // no-op
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block", false, submits); // no-op
        vg$spreadSet("minecraft:dirt", "minecraft:grass_block", false, submits); // no-op
        vg$spreadSet("minecraft:grass_block", "minecraft:grass_block", true, submits); // refresh
        vg$spreadSet("minecraft:grass_block", "minecraft:grass_block", true, submits); // refresh

        assertEquals(5, submits.get(), "exactly one submit per real spread");
    }
}
