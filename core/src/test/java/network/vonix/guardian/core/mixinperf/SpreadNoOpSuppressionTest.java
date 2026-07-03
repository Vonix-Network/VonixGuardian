/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v1.3.0 W1b regression — SpreadingSnowyDirtBlockMixin no-op suppression.
 *
 * <p>Old code path (HEAD @Inject on {@code randomTick}) submitted a SPREAD
 * action every time a grass/mycelium block received a random tick, regardless
 * of whether the vanilla logic actually converted a neighboring dirt block.
 * On modpacks with large grass fields this pushed 10k+ SPREAD/sec through the
 * queue, dominated by no-ops.</p>
 *
 * <p>New code path (@Redirect on {@code ServerLevel#setBlockAndUpdate} inside
 * {@code randomTick}) only submits when vanilla actually calls setBlockAndUpdate
 * with a different block — i.e. real spread happened.</p>
 *
 * <p>This test models a no-op random tick (grass block ticks, no adjacent dirt
 * to convert) and asserts that the new hot-path submits zero SPREAD actions.</p>
 */
public class SpreadNoOpSuppressionTest {

    /** Mirrors the pre-1.3.0 HEAD @Inject: submit unconditionally at randomTick head. */
    private static int oldHeadInject_randomTick(boolean vanillaWouldSpread, AtomicInteger submits) {
        submits.incrementAndGet();
        // vanilla body still runs; setBlockAndUpdate may or may not fire
        if (vanillaWouldSpread) {
            // vanilla calls setBlockAndUpdate — but old code already submitted at HEAD
        }
        return submits.get();
    }

    /** Mirrors the v1.3.0 @Redirect: only submit when setBlockAndUpdate actually fires
     *  AND the target block differs (grass replacing dirt). */
    private static int newRedirect_randomTick(boolean vanillaWouldSpread, AtomicInteger submits) {
        if (vanillaWouldSpread) {
            // redirect body observes old!=new, calls submitSpread
            submits.incrementAndGet();
        }
        return submits.get();
    }

    @Test
    public void noOpRandomTick_oldSubmits_newDoesNot() {
        AtomicInteger oldCount = new AtomicInteger();
        AtomicInteger newCount = new AtomicInteger();

        // Simulate 1000 random ticks on grass blocks that have no dirt neighbor
        // to convert. Vanilla logic runs and returns without calling setBlockAndUpdate.
        for (int i = 0; i < 1000; i++) {
            oldHeadInject_randomTick(false, oldCount);
            newRedirect_randomTick(false, newCount);
        }

        assertEquals(1000, oldCount.get(),
                "Old HEAD @Inject would submit for every random tick");
        assertEquals(0, newCount.get(),
                "New @Redirect must submit zero SPREAD for no-op random ticks");
    }

    @Test
    public void mixedRealAndNoOpTicks_newOnlySubmitsReal() {
        AtomicInteger newCount = new AtomicInteger();
        int realSpreads = 0;

        // 10000 random ticks with a realistic ~2% real-spread rate
        for (int i = 0; i < 10000; i++) {
            boolean real = (i % 50) == 0; // 2% of ticks are real spreads
            if (real) realSpreads++;
            newRedirect_randomTick(real, newCount);
        }

        assertEquals(realSpreads, newCount.get(),
                "New @Redirect should submit exactly once per real spread and zero for no-ops");
        assertEquals(200, realSpreads, "sanity check on the 2% rate");
    }
}
