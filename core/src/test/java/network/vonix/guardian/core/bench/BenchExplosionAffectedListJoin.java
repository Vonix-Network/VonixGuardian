package network.vonix.guardian.core.bench;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.ExplosionJoinWorker;
import network.vonix.guardian.core.event.Sentinel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v1.3.0 W3 — micro-benchmark for the off-thread {@link ExplosionJoinWorker}.
 *
 * <p>Compares:</p>
 * <ol>
 *   <li><b>OLD path.</b> Server thread does {@code getBlockState} + registry
 *       key lookup + {@link StringBuilder} join + {@code EventSubmitter.submitExplosion}
 *       inline. Everything runs on the caller.</li>
 *   <li><b>NEW path.</b> Server thread does only the id-array capture, then
 *       hands the join + submit to {@link ExplosionJoinWorker}. The join and
 *       enqueue happen off-thread.</li>
 * </ol>
 *
 * <p>Explosion size: 5,000 affected blocks. Iteration count: 200 explosions
 * (10 warmup). We measure caller-thread wall time only — that's the axis
 * this wave is optimizing.</p>
 *
 * <p>Target: server-thread work reduced by ≥ 90 %.</p>
 *
 * <p>Runnable via {@code java network.vonix.guardian.core.bench.BenchExplosionAffectedListJoin}
 * from {@code :core:testClasses}. Also exercised by a JUnit shim (see
 * {@code BenchExplosionAffectedListJoinTest}) to lock in the reduction on CI.</p>
 */
public final class BenchExplosionAffectedListJoin {

    private static final int AFFECTED = 5_000;
    private static final int WARMUP = 10;
    private static final int MEASURED = 200;

    public static void main(String[] args) throws Exception {
        Result r = run();
        System.out.printf("BenchExplosionAffectedListJoin (%d explosions × %d blocks)%n",
                MEASURED, AFFECTED);
        System.out.printf("  OLD (server thread join):  %,d ns caller wall time (avg %,d ns / explosion)%n",
                r.oldNanos, r.oldNanos / MEASURED);
        System.out.printf("  NEW (off-thread join):     %,d ns caller wall time (avg %,d ns / explosion)%n",
                r.newNanos, r.newNanos / MEASURED);
        System.out.printf("  Server-thread wall-time reduction: %.2f %% (target ≥ 90 %%)%n",
                r.reductionPercent());
    }

    /** Programmatic API for the JUnit shim. */
    public static Result run() throws Exception {
        // Prepare fixture: pre-fabricated affected list identical between runs.
        int[] xs = new int[AFFECTED];
        int[] ys = new int[AFFECTED];
        int[] zs = new int[AFFECTED];
        String[] ids = new String[AFFECTED];
        for (int i = 0; i < AFFECTED; i++) {
            xs[i] = i;
            ys[i] = 64;
            zs[i] = 0;
            ids[i] = "minecraft:stone";
        }
        UUID actor = UUID.randomUUID();
        // ---------- OLD path: everything on caller ----------
        CountingSubmitter oldRecv = new CountingSubmitter();
        // Warmup
        for (int i = 0; i < WARMUP; i++) oldSyncJoin(oldRecv, actor, xs, ys, zs, ids);
        long tOld0 = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) oldSyncJoin(oldRecv, actor, xs, ys, zs, ids);
        long oldNanos = System.nanoTime() - tOld0;
        // ---------- NEW path: off-thread join ----------
        try (ExplosionJoinWorker w = new ExplosionJoinWorker()) {
            CountingSubmitter newRecv = new CountingSubmitter();
            // Warmup
            for (int i = 0; i < WARMUP; i++) {
                w.submit(newRecv, actor, "creeper", "minecraft:overworld",
                         100, 64, 100, "#tnt", xs, ys, zs, ids, AFFECTED);
            }
            long tNew0 = System.nanoTime();
            for (int i = 0; i < MEASURED; i++) {
                w.submit(newRecv, actor, "creeper", "minecraft:overworld",
                         100, 64, 100, "#tnt", xs, ys, zs, ids, AFFECTED);
            }
            long newNanos = System.nanoTime() - tNew0;
            // Wait for the worker to drain (fairness — but this is not counted).
            waitForCount(newRecv, MEASURED + WARMUP);
            return new Result(oldNanos, newNanos);
        }
    }

    private static void oldSyncJoin(EventSubmitter s, UUID actor,
                                    int[] xs, int[] ys, int[] zs, String[] ids) {
        StringBuilder sb = new StringBuilder();
        int cap = 4096;
        int count = 0;
        for (int i = 0; i < xs.length; i++) {
            if (sb.length() > cap) break;
            if (count++ > 0) sb.append(',');
            sb.append(xs[i]).append(':').append(ys[i]).append(':').append(zs[i])
              .append('=').append(ids[i]);
        }
        s.submitExplosion(actor, "creeper", "minecraft:overworld",
                          100, 64, 100, sb.toString(), "#tnt");
    }

    private static void waitForCount(CountingSubmitter s, int atLeast) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (s.count < atLeast && System.nanoTime() < deadline) {
            try { Thread.sleep(1); } catch (InterruptedException ignored) { break; }
        }
    }

    public record Result(long oldNanos, long newNanos) {
        public double reductionPercent() {
            return 100.0 * (1.0 - (double) newNanos / (double) oldNanos);
        }
    }

    private static final class CountingSubmitter implements EventSubmitter {
        volatile int count;
        @Override public synchronized void submitExplosion(UUID a, String n, String w, int x, int y, int z,
                                                          String j, String s) { count++; }
        // Everything else no-op — see NoopEventSubmitter pattern in ExplosionJoinWorkerTest.
        @Override public void submit(Action a) {}
        @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitContainerChange(UUID u, String n, String w, int x, int y, int z, String i, int d, String s) {}
        @Override public void submitItemDrop(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemPickup(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntityKill(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitChat(UUID u, String n, String w, String m) {}
        @Override public void submitCommand(UUID u, String n, String w, String cmd) {}
        @Override public void submitSign(UUID u, String n, String w, int x, int y, int z, String j) {}
        @Override public void submitSessionJoin(UUID u, String n, String w, String i) {}
        @Override public void submitSessionLeave(UUID u, String n, String w, String r) {}
        @Override public void submitUsernameChange(UUID u, String nn, String w, String on) {}
        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitDispense(UUID u, String n, String w, int x, int y, int z, String i, String s) {}
        @Override public void submitPistonExtend(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitPistonRetract(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketEmpty(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketFill(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitEntityChangeBlock(UUID u, String n, String w, int x, int y, int z, String o, String nb, String s) {}
        @Override public void submitInventoryDeposit(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitInventoryWithdraw(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntitySpawn(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntityInteract(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
        @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}
    }
}
