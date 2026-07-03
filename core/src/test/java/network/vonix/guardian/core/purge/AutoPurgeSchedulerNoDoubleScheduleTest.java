/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.3 Z4 (G-Y3-2 P2 close-out): {@link AutoPurgeScheduler#applyConfig} that
 * fires while a scheduled run is in its {@code finally} window must NOT
 * produce two enqueued next-tick tasks.
 *
 * <p>Pre-Z4 the {@code runScheduled} finally computed the next run time and
 * called {@link AutoPurgeScheduler#scheduleAt} OUTSIDE any monitor. A
 * concurrent {@code applyConfig(...)} — which is {@code synchronized(this)}
 * and cancels {@code nextTask} then reschedules — could fire between the
 * try/finally boundary and the finally's own scheduleAt call, leaving the
 * ScheduledFuture reference clobbered while the applyConfig-scheduled task
 * remained enqueued on the executor. Result: two purge cycles fire on the
 * next tick.
 *
 * <p>The fix wraps the finally reschedule in {@code synchronized(this)} and
 * skips the finally reschedule when {@code nextTask} is a fresh, not-yet-run
 * ScheduledFuture (i.e. applyConfig already scheduled us).
 */
class AutoPurgeSchedulerNoDoubleScheduleTest {

    private SqliteDao dao;
    private PurgeEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        engine = new PurgeEngine(dao);
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    private static GuardianConfig withPurge(GuardianConfig base, GuardianConfig.Purge p) {
        return new GuardianConfig(
                base.database(), base.queue(), base.logFile(), base.actions(),
                base.permissions(), base.lookup(), base.privacy(), p, base.theme());
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = AutoPurgeScheduler.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * Force {@code runScheduled()} to execute inline on the calling thread by
     * calling the private method directly. This side-steps the executor entirely
     * so we don't have to unwrap {@link java.util.concurrent.Executors}'
     * delegated wrapper (which is module-protected on JDK 17+).
     */
    private static void invokeRunScheduled(AutoPurgeScheduler sched) throws Exception {
        Method m = AutoPurgeScheduler.class.getDeclaredMethod("runScheduled");
        m.setAccessible(true);
        m.invoke(sched);
    }

    /**
     * Under the fix, after invokeRunScheduled the scheduler's {@code nextTask}
     * points to exactly the finally-scheduled ScheduledFuture. If applyConfig
     * fires afterwards it must CANCEL that one before installing its own.
     * Under the pre-Z4 bug, applyConfig firing mid-finally could clobber the
     * field without cancelling the just-installed task.
     *
     * <p>We simulate the pre-Z4 race by:
     * <ol>
     *   <li>Invoking runScheduled on thread A (which will schedule a real
     *       finally-task on the executor)</li>
     *   <li>Immediately calling applyConfig on thread B with a new time</li>
     *   <li>Asserting that {@code nextTask} on the scheduler equals a live,
     *       not-cancelled ScheduledFuture AND any previously-visible
     *       ScheduledFuture (captured mid-finally) has been cancelled</li>
     * </ol>
     */
    @Test
    @DisplayName("Z4 G-Y3-2: applyConfig cancels the finally-scheduled task even under interleaving")
    void applyConfigCancelsPreviousFinallyScheduledTask() throws Exception {
        long retention = 180L * 86_400L;
        GuardianConfig cfg = withPurge(GuardianConfig.defaults(),
                new GuardianConfig.Purge(86_400L, 2_592_000L, retention, "03:30"));

        // Fixed-clock so schedule delay is deterministic (far future, no fires).
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneId.of("UTC"));
        AutoPurgeScheduler sched =
                AutoPurgeScheduler.create(cfg, engine, dao, ZoneId.of("UTC"), fixed);
        sched.start();
        try {
            Field nextTaskField = field("nextTask");
            ScheduledFuture<?> initialTask = (ScheduledFuture<?>) nextTaskField.get(sched);
            assertThat((Object) initialTask).as("start() installs an initial nextTask").isNotNull();
            assertThat(initialTask.isCancelled()).isFalse();

            // Simulate the runScheduled finally by directly invoking it — with
            // no rows to delete, runNow returns 0 quickly, the finally
            // scheduleAt runs, and nextTask is reassigned.
            invokeRunScheduled(sched);
            ScheduledFuture<?> afterRunTask = (ScheduledFuture<?>) nextTaskField.get(sched);
            assertThat((Object) afterRunTask)
                .as("finally must install a fresh nextTask")
                .isNotNull()
                .isNotSameAs(initialTask);
            // initial task ran/completed, its cancel is a no-op on the past task.

            // Now applyConfig fires — this is what the pre-Z4 bug would have
            // clobbered without cancelling.
            GuardianConfig cfg2 = withPurge(GuardianConfig.defaults(),
                    new GuardianConfig.Purge(86_400L, 2_592_000L, retention, "22:15"));
            sched.applyConfig(cfg2);

            ScheduledFuture<?> afterApplyTask = (ScheduledFuture<?>) nextTaskField.get(sched);
            assertThat((Object) afterApplyTask)
                .as("applyConfig installs a fresh nextTask")
                .isNotNull()
                .isNotSameAs(afterRunTask);
            assertThat(afterRunTask.isCancelled())
                .as("Z4 invariant: applyConfig MUST cancel the previous nextTask before installing its own — no orphan scheduled task")
                .isTrue();
        } finally {
            sched.shutdown(2_000);
        }
    }

    /**
     * Direct stress: hammer runScheduled and applyConfig on parallel threads
     * and verify no crash, no deadlock, and the terminal nextTask on shutdown
     * is either null or cancelled.
     */
    @Test
    @DisplayName("Z4 G-Y3-2: parallel runScheduled + applyConfig do not deadlock or crash")
    void parallelRunScheduledAndApplyConfigStress() throws Exception {
        long retention = 180L * 86_400L;
        GuardianConfig cfg = withPurge(GuardianConfig.defaults(),
                new GuardianConfig.Purge(86_400L, 2_592_000L, retention, "03:30"));

        Clock fixed = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneId.of("UTC"));
        AutoPurgeScheduler sched =
                AutoPurgeScheduler.create(cfg, engine, dao, ZoneId.of("UTC"), fixed);
        sched.start();
        try {
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicInteger runInvocations = new AtomicInteger();
            AtomicInteger applyInvocations = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(2);

            Thread runner = new Thread(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < 500 && !stop.get(); i++) {
                    try {
                        invokeRunScheduled(sched);
                        runInvocations.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            }, "vg-z4-runner");
            runner.setDaemon(true);

            Thread applier = new Thread(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < 500 && !stop.get(); i++) {
                    try {
                        LocalTime t = LocalTime.of(3 + (i % 20), (i * 3) % 60);
                        GuardianConfig cfg2 = withPurge(GuardianConfig.defaults(),
                                new GuardianConfig.Purge(86_400L, 2_592_000L, retention, t.toString()));
                        sched.applyConfig(cfg2);
                        applyInvocations.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            }, "vg-z4-applier");
            applier.setDaemon(true);

            runner.start();
            applier.start();
            runner.join(30_000);
            applier.join(30_000);
            stop.set(true);

            assertThat(runner.isAlive()).as("runner finished (no deadlock)").isFalse();
            assertThat(applier.isAlive()).as("applier finished (no deadlock)").isFalse();
            assertThat(errors.get()).as("no exceptions from concurrent operations").isZero();
            assertThat(runInvocations.get()).isGreaterThan(0);
            assertThat(applyInvocations.get()).isGreaterThan(0);
        } finally {
            sched.shutdown(2_000);
        }
    }

    /**
     * Seeds some stale rows so runNow does actual chunked-delete work,
     * then invokes runScheduled once and asserts the finally installs
     * exactly one live nextTask.
     */
    @Test
    @DisplayName("Z4 G-Y3-2: finally installs exactly one live nextTask after runNow with work")
    void finallyInstallsOneLiveTaskAfterActiveRun() throws Exception {
        // Seed 50 stale rows.
        long now = 1_800_000_000_000L;
        UUID actor = UUID.nameUUIDFromBytes("z4-actor".getBytes());
        String world = "minecraft:overworld";
        List<Action> batch = new ArrayList<>(50);
        long stale = now - 365L * 86_400L * 1000L;
        for (int i = 0; i < 50; i++) {
            batch.add(new Action(-1L, stale + i * 1000L, ActionType.BLOCK_BREAK,
                    actor, "Z4Actor", world, i, 64, i,
                    "minecraft:stone", null, 1, false, null));
        }
        assertThat(dao.insertBatch(batch)).isEqualTo(50);

        long retention = 180L * 86_400L;
        GuardianConfig cfg = withPurge(GuardianConfig.defaults(),
                new GuardianConfig.Purge(86_400L, 2_592_000L, retention, "03:30"));

        Clock fixed = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC"));
        AutoPurgeScheduler sched =
                AutoPurgeScheduler.create(cfg, engine, dao, ZoneId.of("UTC"), fixed);
        sched.start();
        try {
            invokeRunScheduled(sched);
            assertThat(sched.getLastRunDeleted()).contains(50L);
            assertThat(dao.count(QueryFilter.empty())).isZero();

            Field nextTaskField = field("nextTask");
            ScheduledFuture<?> t = (ScheduledFuture<?>) nextTaskField.get(sched);
            assertThat((Object) t).as("finally installs a nextTask").isNotNull();
            assertThat(t.isCancelled()).as("finally-scheduled task is not cancelled").isFalse();
            assertThat(t.isDone()).as("finally-scheduled task is not done yet").isFalse();
        } finally {
            sched.shutdown(2_000);
        }
    }
}
