/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.bench;

import network.vonix.guardian.core.mixinperf.ConcretePowderMixinModel;
import network.vonix.guardian.core.mixinperf.IceMixinModel;
import network.vonix.guardian.core.mixinperf.LeavesMixinModel;

import java.util.SplittableRandom;

/**
 * v1.3.0 W1c hot-path micro-benchmark.
 *
 * <p>Combined harness for LeavesBlock / IceBlock / ConcretePowder mixin
 * discipline switch (HEAD → @Redirect). The primary signal is the
 * submit-count reduction reported per block class.</p>
 *
 * <p>Runs from a plain {@code public static void main} — no JMH dependency.
 * Intended to be run manually via {@code java network.vonix.guardian.core.bench.OtherMixinsHotPathBench}
 * on the test classpath, or as a JUnit shim (see {@code OtherMixinsHotPathBenchTest}).
 * The harness enforces the W1c reduction targets and prints a report.</p>
 *
 * <p>Targets:
 * <ul>
 *     <li>Leaves: ≥70% submit reduction</li>
 *     <li>Ice: ≥90% submit reduction</li>
 *     <li>ConcretePowder: ≥95% submit reduction</li>
 * </ul>
 * </p>
 */
public final class OtherMixinsHotPathBench {

    private static final int ITER = 200_000;

    private OtherMixinsHotPathBench() {}

    public static void main(String[] args) {
        Result leaves = benchLeaves();
        Result ice = benchIce();
        Result concrete = benchConcrete();

        System.out.println("=== v1.3.0 W1c hot-path bench ===");
        System.out.printf("Leaves:         old=%d new=%d reduction=%.1f%% (target >= 70%%)%n",
                leaves.oldSubmits, leaves.newSubmits, leaves.reductionPct());
        System.out.printf("Ice:            old=%d new=%d reduction=%.1f%% (target >= 90%%)%n",
                ice.oldSubmits, ice.newSubmits, ice.reductionPct());
        System.out.printf("ConcretePowder: old=%d new=%d reduction=%.1f%% (target >= 95%%)%n",
                concrete.oldSubmits, concrete.newSubmits, concrete.reductionPct());

        if (leaves.reductionPct() < 70.0) throw new AssertionError("Leaves reduction below 70%");
        if (ice.reductionPct() < 90.0) throw new AssertionError("Ice reduction below 90%");
        if (concrete.reductionPct() < 95.0) throw new AssertionError("ConcretePowder reduction below 95%");
        System.out.println("All W1c targets met.");
    }

    public static Result benchLeaves() {
        SplittableRandom rnd = new SplittableRandom(0xBABEL);
        int oldSubmits = 0, newSubmits = 0;
        for (int i = 0; i < ITER; i++) {
            int roll = rnd.nextInt(100);
            boolean persistent = roll < 60;
            int distance = persistent ? 3 : (roll < 85 ? rnd.nextInt(6) + 1 : 7);
            boolean decayCandidate = !persistent && distance >= 7;
            boolean removed = decayCandidate && rnd.nextInt(10) == 0;
            if (LeavesMixinModel.oldHeadSubmit(persistent, distance)) oldSubmits++;
            if (LeavesMixinModel.newRedirectSubmit(removed, removed)) newSubmits++;
        }
        return new Result("Leaves", oldSubmits, newSubmits);
    }

    public static Result benchIce() {
        SplittableRandom rnd = new SplittableRandom(0xC01DL);
        int oldSubmits = 0, newSubmits = 0;
        for (int i = 0; i < ITER; i++) {
            boolean actuallyMelted = rnd.nextInt(20) == 0;
            if (IceMixinModel.oldHeadSubmit()) oldSubmits++;
            if (IceMixinModel.newRedirectSubmitRemove(actuallyMelted, actuallyMelted)) newSubmits++;
        }
        return new Result("Ice", oldSubmits, newSubmits);
    }

    public static Result benchConcrete() {
        SplittableRandom rnd = new SplittableRandom(0xC0FFEEL);
        int oldSubmits = 0, newSubmits = 0;
        for (int i = 0; i < ITER; i++) {
            boolean solidified = rnd.nextInt(50) == 0;
            if (ConcretePowderMixinModel.oldHeadSubmit()) oldSubmits++;
            if (ConcretePowderMixinModel.newRedirectSubmit(solidified, true, true, solidified)) newSubmits++;
        }
        return new Result("Concrete", oldSubmits, newSubmits);
    }

    public static final class Result {
        public final String name;
        public final int oldSubmits;
        public final int newSubmits;

        Result(String name, int oldSubmits, int newSubmits) {
            this.name = name;
            this.oldSubmits = oldSubmits;
            this.newSubmits = newSubmits;
        }

        public double reductionPct() {
            if (oldSubmits == 0) return 0.0;
            return (1.0 - ((double) newSubmits / oldSubmits)) * 100.0;
        }
    }
}
