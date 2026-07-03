/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.bench;

import network.vonix.guardian.core.mixinperf.FireGuardHarness;

/**
 * v1.3.0 W1a JMH-style micro-benchmark harness (no JMH dependency).
 *
 * <p>Simulates 100 fire blocks × 20 tps × 60 s = 120,000 potential BURN submits,
 * comparing:
 * <ol>
 *   <li><b>Old-style HEAD injection</b> — a submit fires on every random tick
 *       regardless of whether fire consumed anything.</li>
 *   <li><b>New-style guarded submission</b> — only submits when
 *       {@code removeBlock}/{@code setBlock} actually returned true and the
 *       replaced/removed block was not air. A realistic 10% actual burn rate
 *       is used (matches the modded server observation captured in the
 *       2026-07-03 async/perf audit).</li>
 * </ol>
 *
 * <p>Prints one line summary. Passes if the new-style submit count is ≥80 %
 * lower than the old-style count (target from NIGHTSHIFT.md).</p>
 */
public final class FireBurnHotPathBench {

    private static final int FIRE_BLOCKS = 100;
    private static final int TPS         = 20;
    private static final int SECONDS     = 60;
    private static final long ATTEMPTS   = (long) FIRE_BLOCKS * TPS * SECONDS; // 120_000
    private static final int BURN_RATE_PCT = 10;

    public static void main(String[] args) {
        Result oldResult = runOldStyle();
        Result newResult = runNewStyle();

        double reduction = 100.0 * (oldResult.submits - newResult.submits) / (double) oldResult.submits;
        System.out.printf(
                "Old: %d submits, %d ms | New: %d submits, %d ms | Reduction: %.1f%%%n",
                oldResult.submits, oldResult.millis,
                newResult.submits, newResult.millis,
                reduction);

        if (reduction < 80.0) {
            System.err.println("FAIL: reduction " + reduction + "% is below the 80% target");
            System.exit(1);
        }
    }

    /**
     * Pre-1.3.0 behavior: HEAD inject on {@code FireBlock.tick} unconditionally
     * fires a BURN submit per random tick.
     */
    static Result runOldStyle() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();
        long t0 = System.nanoTime();
        for (long i = 0; i < ATTEMPTS; i++) {
            // Simulate an unconditional submit exactly like the old HEAD inject.
            rec.submitBurn(null, "#fire", "minecraft:overworld",
                    (int) (i % 128), 64, 0, "minecraft:fire", "world:burn");
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        return new Result(rec.burnCount.get(), millis);
    }

    /**
     * v1.3.0 behavior: the {@code @Redirect} guard only submits when
     * {@code removeBlock} actually returned {@code true} AND the replaced
     * block was not air. In a realistic modded-server burning-structure
     * scenario, only ~10 % of random ticks actually consume a block.
     */
    static Result runNewStyle() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();
        long t0 = System.nanoTime();
        for (long i = 0; i < ATTEMPTS; i++) {
            boolean actuallyBurned = (i % (100 / BURN_RATE_PCT) == 0);
            FireGuardHarness.guardedRemoveBurn(rec,
                    "minecraft:overworld", (int) (i % 128), 64, 0,
                    "minecraft:oak_planks", false, actuallyBurned);
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        return new Result(rec.burnCount.get(), millis);
    }

    static final class Result {
        final int submits;
        final long millis;
        Result(int submits, long millis) {
            this.submits = submits;
            this.millis = millis;
        }
    }
}
