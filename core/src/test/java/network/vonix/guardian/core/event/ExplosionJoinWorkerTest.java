package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W3 — regression tests for {@link ExplosionJoinWorker}.
 *
 * <p>Verifies:</p>
 * <ol>
 *   <li>Small explosion joins into a single EXPLOSION row.</li>
 *   <li>A 5,000-block chain is chunked into multiple EXPLOSION submits, all
 *       sharing the same center + source tag.</li>
 *   <li>Null / empty ids are skipped, not thrown on.</li>
 *   <li>Async worker frees the caller thread (submit returns before join completes).</li>
 * </ol>
 */
class ExplosionJoinWorkerTest {

    /** Records only EXPLOSION submits; everything else no-op. */
    private static final class RecordingExplosionSubmitter extends NoopEventSubmitter {
        record Row(UUID actor, String actorName, String worldId,
                   int x, int y, int z, String affected, String sourceTag) {}
        final List<Row> rows = new ArrayList<>();
        @Override
        public synchronized void submitExplosion(UUID actor, String actorName, String worldId,
                                                 int x, int y, int z, String affected, String sourceTag) {
            rows.add(new Row(actor, actorName, worldId, x, y, z, affected, sourceTag));
        }
    }

    private static ExplosionJoinWorker syncWorker() {
        return new ExplosionJoinWorker(new SameThreadExecutor());
    }

    private static final class SameThreadExecutor extends java.util.concurrent.AbstractExecutorService {
        @Override public void shutdown() {}
        @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable r) { r.run(); }
    }

    @Test
    void smallExplosion_singleRow() {
        ExplosionJoinWorker w = syncWorker();
        RecordingExplosionSubmitter s = new RecordingExplosionSubmitter();
        int[] xs = {0, 1, 2};
        int[] ys = {64, 64, 64};
        int[] zs = {0, 0, 0};
        String[] ids = {"minecraft:stone", "minecraft:dirt", "minecraft:sand"};
        w.submit(s, UUID.randomUUID(), "creeper", "minecraft:overworld",
                 5, 64, 5, "#tnt", xs, ys, zs, ids, 3);
        assertThat(s.rows).hasSize(1);
        assertThat(s.rows.get(0).sourceTag()).isEqualTo("#tnt");
        assertThat(s.rows.get(0).affected())
            .isEqualTo("0:64:0=minecraft:stone,1:64:0=minecraft:dirt,2:64:0=minecraft:sand");
        assertThat(s.rows.get(0).x()).isEqualTo(5);
        assertThat(w.joinedCount()).isEqualTo(1);
        assertThat(w.chunkCount()).isEqualTo(1);
    }

    @Test
    void largeExplosion_chunkedRows_shareCenter() {
        ExplosionJoinWorker w = syncWorker();
        RecordingExplosionSubmitter s = new RecordingExplosionSubmitter();
        int N = 5_000;
        int[] xs = new int[N];
        int[] ys = new int[N];
        int[] zs = new int[N];
        String[] ids = new String[N];
        for (int i = 0; i < N; i++) {
            xs[i] = i;
            ys[i] = 64;
            zs[i] = 0;
            ids[i] = "minecraft:stone";
        }
        UUID actor = UUID.randomUUID();
        w.submit(s, actor, "creeper", "minecraft:overworld",
                 100, 64, 100, "#tnt", xs, ys, zs, ids, N);
        int expected = (N + ExplosionJoinWorker.MAX_ENTRIES_PER_CHUNK - 1)
                / ExplosionJoinWorker.MAX_ENTRIES_PER_CHUNK;
        assertThat(s.rows.size()).isBetween(expected, expected + 2);
        for (RecordingExplosionSubmitter.Row r : s.rows) {
            assertThat(r.x()).isEqualTo(100);
            assertThat(r.y()).isEqualTo(64);
            assertThat(r.z()).isEqualTo(100);
            assertThat(r.actor()).isEqualTo(actor);
            assertThat(r.actorName()).isEqualTo("creeper");
            assertThat(r.sourceTag()).isEqualTo("#tnt");
            assertThat(r.affected().length()).isLessThanOrEqualTo(4096);
        }
    }

    @Test
    void nullActorName_defaultsToExplosionSentinel() {
        ExplosionJoinWorker w = syncWorker();
        RecordingExplosionSubmitter s = new RecordingExplosionSubmitter();
        w.submit(s, null, null, "minecraft:overworld",
                 0, 0, 0, null,
                 new int[]{0}, new int[]{0}, new int[]{0}, new String[]{"minecraft:stone"}, 1);
        assertThat(s.rows).hasSize(1);
        assertThat(s.rows.get(0).actorName()).isEqualTo(Sentinel.EXPLOSION);
        assertThat(s.rows.get(0).sourceTag()).isEqualTo(Sentinel.EXPLOSION);
    }

    @Test
    void emptyBlockIds_skipped_notThrown() {
        ExplosionJoinWorker w = syncWorker();
        RecordingExplosionSubmitter s = new RecordingExplosionSubmitter();
        w.submit(s, UUID.randomUUID(), "creeper", "minecraft:overworld",
                 0, 0, 0, "#tnt",
                 new int[]{0, 1, 2, 3},
                 new int[]{64, 64, 64, 64},
                 new int[]{0, 0, 0, 0},
                 new String[]{"minecraft:stone", null, "", "minecraft:dirt"},
                 4);
        assertThat(s.rows).hasSize(1);
        assertThat(s.rows.get(0).affected())
            .isEqualTo("0:64:0=minecraft:stone,3:64:0=minecraft:dirt");
    }

    @Test
    void countZero_noSubmit() {
        ExplosionJoinWorker w = syncWorker();
        RecordingExplosionSubmitter s = new RecordingExplosionSubmitter();
        w.submit(s, null, "x", "w", 0, 0, 0, "#tnt",
                 new int[10], new int[10], new int[10], new String[10], 0);
        assertThat(s.rows).isEmpty();
        assertThat(w.joinedCount()).isEqualTo(0);
    }

    @Test
    void asyncWorker_freesCallerThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-explosion-join");
            t.setDaemon(true);
            return t;
        });
        try (ExplosionJoinWorker w = new ExplosionJoinWorker(pool)) {
            CountDownLatch gate = new CountDownLatch(1);
            AtomicInteger step = new AtomicInteger();
            AtomicInteger callerReturnedAt = new AtomicInteger();
            AtomicInteger submitObservedAt = new AtomicInteger();
            NoopEventSubmitter blocking = new NoopEventSubmitter() {
                @Override public void submitExplosion(UUID a, String n, String w2, int x, int y, int z,
                                                     String affected, String st) {
                    try { gate.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    submitObservedAt.set(step.incrementAndGet());
                }
            };
            w.submit(blocking, UUID.randomUUID(), "creeper", "minecraft:overworld",
                     0, 64, 0, "#tnt",
                     new int[]{0}, new int[]{0}, new int[]{0}, new String[]{"minecraft:stone"}, 1);
            callerReturnedAt.set(step.incrementAndGet());
            gate.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (submitObservedAt.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(callerReturnedAt.get()).isEqualTo(1);
            assertThat(submitObservedAt.get()).isEqualTo(2);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Full no-op {@link EventSubmitter} stub — every method throws {@link UnsupportedOperationException}
     * except {@link #submit(Action)}, {@link #submitExplosion} (test-specific override target).
     */
    static class NoopEventSubmitter implements EventSubmitter {
        @Override public void submit(Action a) {}
        @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitContainerChange(UUID u, String n, String w, int x, int y, int z, String i, int d, String s) {}
        @Override public void submitItemDrop(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemPickup(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntityKill(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitExplosion(UUID u, String n, String w, int x, int y, int z, String j, String s) {}
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
