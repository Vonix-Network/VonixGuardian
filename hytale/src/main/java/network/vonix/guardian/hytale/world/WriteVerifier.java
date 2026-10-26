/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.world;

import network.vonix.guardian.hytale.spi.BlockSnapshot;
import network.vonix.guardian.hytale.spi.WorldBlockAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Post-rollback convergence: re-read every position the batch intended to write,
 * repair what did not stick, and keep retrying until the world agrees or the
 * pass budget runs out.
 *
 * <p>Two hard rules learned on a live server:
 * <ol>
 *   <li><b>All reads and writes happen on the owning world's thread.</b> Reading
 *       a chunk section off-thread returns {@code null}, which made an earlier
 *       version of this class report 1046/1046 positions "unverifiable".</li>
 *   <li><b>Never block the world thread.</b> Each pass is submitted as one task;
 *       the caller (a command worker, not the tick thread) waits with a timeout,
 *       and the settle delay between passes sleeps off-thread.</li>
 * </ol>
 *
 * <p>A single failed mutation is enough to leave a batch {@code PARTIAL}, so the
 * verifier's job is convergence, not bookkeeping: it retries the exact positions
 * that disagree, pass after pass, and reports honestly when it cannot.
 */
public final class WriteVerifier {

    private static final Logger LOG = Logger.getLogger(WriteVerifier.class.getName());

    /** Passes attempted per call, including the first observation pass. */
    private static final int DEFAULT_PASSES = 4;

    /** Delay between passes so block physics/support updates settle (off-thread). */
    private static final long SETTLE_MILLIS = 120L;

    /** How long one pass may occupy the world thread before we give up on it. */
    private static final long PASS_TIMEOUT_MILLIS = 20_000L;

    private static final int SAMPLE_LIMIT = 3;

    /**
     * Outcome of one convergence run.
     *
     * @param checked      positions considered
     * @param unverifiable positions that still could not be read back (world/chunk unavailable)
     * @param mismatched   positions that disagreed with the intent at least once
     * @param repaired     positions agreement was reached for by a repair write
     * @param unfixed      positions still disagreeing when the pass budget ran out
     * @param dropped      intents the recorder dropped (cap hit) — makes the run partial
     * @param passes       passes actually used
     * @param timedOut     a pass exceeded {@link #PASS_TIMEOUT_MILLIS}
     */
    public record Report(int checked, int unverifiable, int mismatched, int repaired,
                         int unfixed, long dropped, int passes, boolean timedOut) {

        public boolean converged() {
            return mismatched == 0;
        }

        /** True only when nothing disagreed and nothing was lost. */
        public boolean clean() {
            return mismatched == 0 && dropped == 0L && !timedOut;
        }

        public String summary() {
            return "checked=" + checked
                + " unverifiable=" + unverifiable
                + " mismatched=" + mismatched
                + " repaired=" + repaired
                + " unfixed=" + unfixed
                + " dropped=" + dropped
                + " passes=" + passes
                + (timedOut ? " timedOut=true" : "");
        }
    }

    private WriteVerifier() {
    }

    /**
     * Converge the recorded writes.
     *
     * @param world       live block access
     * @param worldThread executor that lands work on the owning world's thread
     * @return the run report; never throws
     */
    public static Report converge(WorldBlockAccess world, Executor worldThread) {
        return converge(world, worldThread, DEFAULT_PASSES);
    }

    /**
     * Converge the recorded writes with an explicit pass budget.
     *
     * @param world       live block access
     * @param worldThread executor that lands work on the owning world's thread
     * @param passes      maximum passes (at least 1)
     * @return the run report; never throws
     */
    public static Report converge(WorldBlockAccess world, Executor worldThread, int passes) {
        List<HytaleBlockAccess.PendingWrite> pending = HytaleBlockAccess.drainPending();
        long dropped = HytaleBlockAccess.drainPendingDrops();
        int budget = Math.max(1, passes);
        if (pending.isEmpty()) {
            return new Report(0, 0, 0, 0, 0, dropped, 0, false);
        }
        // The repair writes record themselves; drop that bookkeeping as we go.
        int mismatched = 0;
        AtomicInteger repaired = new AtomicInteger();
        List<HytaleBlockAccess.PendingWrite> outstanding = new ArrayList<>(pending);
        boolean timedOut = false;
        int used = 0;
        for (int pass = 0; pass < budget && !outstanding.isEmpty(); pass++) {
            used = pass + 1;
            List<HytaleBlockAccess.PendingWrite> stillWrong = new ArrayList<>();
            boolean firstObservation = pass == 0;
            AtomicInteger firstPassMismatch = new AtomicInteger();
            boolean completed = runPass(world, worldThread, outstanding, stillWrong,
                    firstObservation, firstPassMismatch, repaired);
            if (firstObservation) {
                mismatched = firstPassMismatch.get();
            }
            HytaleBlockAccess.drainPending(); // repair writes' own intents
            if (!completed) {
                timedOut = true;
                outstanding = stillWrong;
                break;
            }
            outstanding = stillWrong;
            if (!outstanding.isEmpty() && pass + 1 < budget) {
                sleepQuietly(SETTLE_MILLIS);
            }
        }
        int unverifiable = 0;
        int unfixed = 0;
        // Final classification of what is left: unreadable vs still wrong.
        AtomicInteger[] counters = new AtomicInteger[] {new AtomicInteger(), new AtomicInteger()};
        if (!outstanding.isEmpty()) {
            runClassifyPass(world, worldThread, outstanding, counters);
        }
        unverifiable = counters[0].get();
        unfixed = counters[1].get();
        return new Report(pending.size(), unverifiable, mismatched, repaired.get(),
                unfixed, dropped, used, timedOut);
    }

    private static boolean runPass(WorldBlockAccess world,
                                   Executor worldThread,
                                   List<HytaleBlockAccess.PendingWrite> targets,
                                   List<HytaleBlockAccess.PendingWrite> stillWrongOut,
                                   boolean firstObservation,
                                   AtomicInteger firstPassMismatch,
                                   AtomicInteger repaired) {
        CountDownLatch done = new CountDownLatch(1);
        int[] samples = {0};
        try {
            worldThread.execute(() -> {
                try {
                    for (HytaleBlockAccess.PendingWrite write : targets) {
                        BlockSnapshot actual = safeRead(world, write);
                        if (actual == null) {
                            stillWrongOut.add(write);
                            continue;
                        }
                        boolean agreed = sameState(actual, write.expected());
                        if (agreed) {
                            continue;
                        }
                        if (firstObservation && firstPassMismatch.getAndIncrement() < SAMPLE_LIMIT) {
                            LOG.log(Level.INFO, "verify mismatch at " + write.x() + "," + write.y()
                                    + "," + write.z() + ": expected=" + describe(write.expected())
                                    + " actual=" + describe(actual));
                        }
                        boolean fixed = false;
                        try {
                            if (world.write(write.worldId(), write.x(), write.y(), write.z(), write.expected())) {
                                BlockSnapshot after = safeRead(world, write);
                                fixed = after != null && sameState(after, write.expected());
                            }
                        } catch (RuntimeException e) {
                            fixed = false;
                        }
                        if (fixed) {
                            repaired.incrementAndGet();
                        } else {
                            stillWrongOut.add(write);
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
            if (!done.await(PASS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING, "verification pass exceeded " + PASS_TIMEOUT_MILLIS + "ms");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "verification pass could not be scheduled", e);
            return false;
        }
    }

    private static void runClassifyPass(WorldBlockAccess world,
                                        Executor worldThread,
                                        List<HytaleBlockAccess.PendingWrite> targets,
                                        AtomicInteger[] counters) {
        CountDownLatch done = new CountDownLatch(1);
        try {
            worldThread.execute(() -> {
                try {
                    for (HytaleBlockAccess.PendingWrite write : targets) {
                        BlockSnapshot actual = safeRead(world, write);
                        if (actual == null) {
                            counters[0].incrementAndGet();
                        } else if (!sameState(actual, write.expected())) {
                            counters[1].incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
            done.await(PASS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "classification pass skipped", e);
        } finally {
            HytaleBlockAccess.drainPending();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static BlockSnapshot safeRead(WorldBlockAccess world, HytaleBlockAccess.PendingWrite write) {
        try {
            return world.read(write.worldId(), write.x(), write.y(), write.z());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describe(BlockSnapshot snapshot) {
        return snapshot.blockId() + "/" + snapshot.rotation() + (snapshot.isAir() ? " (air)" : "");
    }

    private static boolean sameState(BlockSnapshot actual, BlockSnapshot expected) {
        if (actual.isAir() && expected.isAir()) {
            return true;
        }
        if (!actual.blockId().equals(expected.blockId())) {
            return false;
        }
        return sameRotation(actual.rotation(), expected.rotation());
    }

    /**
     * Rotation text comes from different code paths (a stored action string vs the
     * canonical form the section reports), so compare the parsed index when both
     * sides parse and fall back to text only when one does not. An unspecified
     * rotation on either side is not a disagreement.
     */
    private static boolean sameRotation(String actual, String expected) {
        if (actual == null || actual.isBlank() || expected == null || expected.isBlank()) {
            return true;
        }
        Integer a = parseOrNull(actual);
        Integer e = parseOrNull(expected);
        if (a != null && e != null) {
            return a.equals(e);
        }
        return actual.equals(expected);
    }

    private static Integer parseOrNull(String rotation) {
        try {
            return HytaleBlockAccess.parseRotation(rotation);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
