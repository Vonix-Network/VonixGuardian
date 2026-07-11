package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.GuardianDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background daily auto-purge daemon (W3-B4) — CoreProtect Patreon 24+ parity.
 *
 * <p>Runs a single scheduled task on a daemon {@link ScheduledExecutorService}
 * that wakes at the operator-configured {@code HH:mm} (server local time)
 * every day and deletes {@code vg_actions} rows older than
 * {@code purge.autoPurgeSeconds}.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>Chunked:</b> {@code DELETE ... WHERE ts &lt; ? LIMIT 10_000} in a
 *       loop with a 200ms pause between chunks, until a chunk returns 0 rows.
 *       This mirrors CP's incremental strategy — the goal is to never hold a
 *       write lock long enough to matter to the tick loop.</li>
 *   <li><b>Mutex-safe:</b> uses {@link PurgeEngine#mutex()} via {@code tryLock}
 *       — if the lock is held (manual {@code /vg purge} in flight, another
 *       auto-purge still winding down, or a migration owning the connection),
 *       the run is skipped, an INFO line is emitted, and the next run is
 *       scheduled for tomorrow at the same {@code HH:mm}.</li>
 *   <li><b>Pause-safe:</b> {@link #isEnabled()} reflects the config toggle;
 *       {@link #shutdown(long)} cancels the schedule and stops chunking mid-run
 *       (via an atomic stop flag) so server shutdown never blocks on purge.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   AutoPurgeScheduler sched = AutoPurgeScheduler.create(cfg, purgeEngine, dao);
 *   sched.start();                    // Guardian.boot
 *   sched.shutdown(5_000L);           // Guardian.close (5s await)
 * </pre>
 *
 * <p>{@link #runNow()} is exposed for tests and forces a single synchronous
 * chunked purge cycle on the calling thread, using the same code path as the
 * scheduled run.
 */
public final class AutoPurgeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPurgeScheduler.class);

    /** Rows per DELETE chunk — mirrors CP's incremental cadence. */
    public static final int CHUNK_LIMIT = 10_000;

    /** Milliseconds paused between chunks to let other DB work through. */
    public static final long CHUNK_PAUSE_MS = 200L;

    /** DateTimeFormatter used by log lines. */
    private static final DateTimeFormatter LOG_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final PurgeEngine purgeEngine;
    private final GuardianDao dao;
    // v1.3.2 Y3: retentionSeconds / runTime / enabled were final in 1.3.1. They
    // are now volatile so applyConfig(...) can hot-swap them without rebuilding
    // the scheduler on /vg reload. zone + clock stay final — swapping timezone
    // mid-run is a restart concern (nightly cadence would smear).
    private volatile long retentionSeconds;
    private volatile LocalTime runTime;
    private final ZoneId zone;
    private final Clock clock;
    private volatile boolean enabled;

    private final AtomicLong totalPurgedSinceRestart = new AtomicLong();
    private final AtomicLong lastRunDeleted = new AtomicLong(-1L);
    private volatile boolean stopRequested = false;

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> nextTask;
    // v1.3.3 Z4 (G-Y3-2): monotonically-increasing generation stamp for every
    // successful scheduleAt call. Guarded by {@code synchronized(this)}. Used
    // by {@link #runScheduled()}'s finally guard to detect whether a
    // concurrent {@link #applyConfig(GuardianConfig)} already installed a
    // fresh nextTask while our runNow was in flight — if the generation
    // observed at finally entry differs from the one captured at run entry,
    // applyConfig owns the reschedule and we must NOT double-book.
    private long scheduleGen;

    private AutoPurgeScheduler(PurgeEngine purgeEngine,
                               GuardianDao dao,
                               long retentionSeconds,
                               LocalTime runTime,
                               ZoneId zone,
                               Clock clock,
                               boolean enabled) {
        this.purgeEngine = purgeEngine;
        this.dao = dao;
        this.retentionSeconds = retentionSeconds;
        this.runTime = runTime;
        this.zone = zone;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Build a scheduler from the loaded config. If {@code purge.autoPurgeSeconds}
     * is {@code 0} the returned instance is a no-op (disabled) whose
     * {@link #start()} logs a single line and does nothing.
     */
    public static AutoPurgeScheduler create(GuardianConfig cfg,
                                            PurgeEngine engine,
                                            GuardianDao dao) {
        return create(cfg, engine, dao, ZoneId.systemDefault(), Clock.systemDefaultZone());
    }

    /** Test-friendly overload accepting an explicit zone + clock. */
    public static AutoPurgeScheduler create(GuardianConfig cfg,
                                            PurgeEngine engine,
                                            GuardianDao dao,
                                            ZoneId zone,
                                            Clock clock) {
        Objects.requireNonNull(cfg, "cfg");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(dao, "dao");
        long retention = cfg.purge().autoPurgeSeconds();
        String hhmm = cfg.purge().autoPurgeTime();
        LocalTime t;
        try {
            t = LocalTime.parse(hhmm);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "purge.autoPurgeTime is not a valid HH:mm value: " + hhmm, e);
        }
        boolean enabled = retention > 0L;
        return new AutoPurgeScheduler(engine, dao, retention, t, zone, clock, enabled);
    }

    /** Compute the next {@link ZonedDateTime} at {@code hhmm} on or after {@code now}. */
    public static ZonedDateTime nextRunOf(LocalTime hhmm, ZoneId zone, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        ZonedDateTime candidate = ZonedDateTime.of(LocalDateTime.of(today, hhmm), zone);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /** Start the scheduler. Safe to invoke once per instance. */
    public synchronized void start() {
        if (!enabled) {
            LOG.info("AutoPurgeScheduler DISABLED (purge.autoPurgeSeconds = 0)");
            return;
        }
        if (exec != null && !exec.isShutdown()) {
            LOG.warn("AutoPurgeScheduler.start() called twice; ignoring");
            return;
        }
        stopRequested = false;
        nextTask = null;
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VonixGuardian-AutoPurge");
            t.setDaemon(true);
            return t;
        });
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        ZonedDateTime next = nextRunOf(runTime, zone, now);
        LOG.info("AutoPurgeScheduler ENABLED (retention={}s, runAt={}, nextRun={})",
                retentionSeconds, runTime, LOG_FMT.format(next));
        scheduleAt(next);
    }

    private void scheduleAt(ZonedDateTime when) {
        long delayMs = Math.max(0L, Duration.between(ZonedDateTime.now(clock.withZone(zone)), when).toMillis());
        nextTask = exec.schedule(this::runScheduled, delayMs, TimeUnit.MILLISECONDS);
        // v1.3.3 Z4 (G-Y3-2): stamp the generation so runScheduled's finally
        // can detect a concurrent applyConfig-installed reschedule. Guarded
        // by {@code synchronized(this)} on all callers (start / applyConfig /
        // runScheduled finally).
        scheduleGen++;
    }

    private void runScheduled() {
        // v1.3.3 Z4 (G-Y3-2 P2 close-out): capture the schedule generation at
        // run entry under the sync monitor. In the finally we re-read it; if
        // it changed, a concurrent applyConfig(...) has already installed a
        // fresh nextTask and we MUST NOT double-book.
        final long myGen;
        synchronized (this) {
            myGen = scheduleGen;
        }
        try {
            runNow();
        } catch (Throwable t) {
            LOG.error("AutoPurgeScheduler run threw", t);
        } finally {
            // Always reschedule for tomorrow — even after a skip or error —
            // UNLESS a concurrent applyConfig owned the reschedule.
            //
            // v1.3.3 Z4 (G-Y3-2 P2 close-out): synchronize the entire finally
            // reschedule on the same monitor as {@link #applyConfig(GuardianConfig)},
            // {@link #start()}, and {@link #shutdown(long)}. Without the guard
            // there is a race window between the try/finally boundary and the
            // scheduleAt call:
            //
            //   T0  runNow() returns; we enter finally
            //   T1  applyConfig() fires on another thread, cancels the
            //       just-completed nextTask (cancel is a no-op on the running
            //       task), sets a NEW nextTask via its own scheduleAt (call it
            //       TASK-A)
            //   T2  finally computes {@code next} and writes a SECOND nextTask
            //       via scheduleAt (call it TASK-B), clobbering the field
            //       reference to TASK-A
            //
            // TASK-A's ScheduledFuture reference is gone from the field — but
            // TASK-A remains enqueued on the executor and will fire, producing
            // a double-schedule (two purge cycles competing for the same
            // retention horizon, both under the purge mutex — one succeeds,
            // one wastes a tryLock cycle and logs a spurious "mutex held" skip).
            //
            // Fix: the generation stamp captured at run entry is compared to
            // the current generation inside the synchronized finally block.
            // If applyConfig fired between runNow-return and this section,
            // {@code scheduleGen > myGen} and we skip our own scheduleAt.
            // Otherwise we schedule as usual. The sync guarantees no
            // interleaving with concurrent applyConfig for the check itself.
            synchronized (this) {
                if (exec == null || exec.isShutdown() || !enabled) {
                    return;
                }
                if (scheduleGen != myGen) {
                    // applyConfig(...) already installed a fresh next-run task.
                    // Do NOT re-schedule; that would clobber it and leave a
                    // ghost ScheduledFuture on the executor.
                    return;
                }
                ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
                ZonedDateTime next = nextRunOf(runTime, zone, now);
                LOG.info("AutoPurgeScheduler next run scheduled at {}", LOG_FMT.format(next));
                scheduleAt(next);
            }
        }
    }

    /**
     * Force one synchronous chunked purge cycle on the calling thread.
     *
     * <p>Returns the number of rows deleted (0 when the mutex was busy, when
     * nothing older than the retention horizon exists, or when the scheduler
     * is disabled).
     *
     * @return rows deleted in this cycle
     * @throws Exception on DAO failure
     */
    public long runNow() throws Exception {
        if (!enabled) {
            return 0L;
        }
        var mutex = purgeEngine.mutex();
        if (!mutex.tryLock()) {
            LOG.info("AutoPurgeScheduler skipping run — purge mutex held (manual purge or migration in flight)");
            lastRunDeleted.set(0L);
            return 0L;
        }
        try {
            long cutoff = clock.millis() - (retentionSeconds * 1000L);
            LOG.info("AutoPurgeScheduler starting chunked purge (cutoffMs={}, chunk={}, pauseMs={})",
                    cutoff, CHUNK_LIMIT, CHUNK_PAUSE_MS);
            long total = 0L;
            int rounds = 0;
            while (!stopRequested) {
                long removed = dao.purgeOlderThan(cutoff, CHUNK_LIMIT);
                total += removed;
                rounds++;
                if (removed == 0L) {
                    break;
                }
                try {
                    Thread.sleep(CHUNK_PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.info("AutoPurgeScheduler interrupted mid-cycle (deleted={} rounds={})",
                            total, rounds);
                    break;
                }
            }
            lastRunDeleted.set(total);
            totalPurgedSinceRestart.addAndGet(total);
            LOG.info("AutoPurgeScheduler finished chunked purge (deleted={}, rounds={})", total, rounds);
            return total;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * Hot-swap the running scheduler's retention window and daily run time
     * (v1.3.2 Y3, P2-4 close-out).
     *
     * <p>Called from {@code Guardian.reloadConfig} when
     * {@code cfg.purge().autoPurgeSeconds()} or
     * {@code cfg.purge().autoPurgeTime()} changed. The previous behaviour
     * (pre-1.3.2) enters the new value into the merged config record but leaves
     * the running daemon on its original schedule until server restart — an
     * operator who edits {@code autoPurgeTime} to move the nightly run out of
     * a raid window would silently keep hitting the old window.
     *
     * <h3>Semantics</h3>
     * <ul>
     *   <li>If the scheduler was disabled (retention=0) and the new config
     *       enables it: build the executor and schedule the first run.</li>
     *   <li>If the scheduler was enabled and the new config disables it:
     *       cancel the next task and shut the executor down.</li>
     *   <li>Otherwise: cancel {@code nextTask} and reschedule under the new
     *       {@link #runTime}.</li>
     * </ul>
     *
     * <p>{@code retentionSeconds} is declared {@code volatile} and read INSIDE
     * {@link #runNow()} (captured to a local {@code cutoff} at the start of the
     * chunked-DELETE loop). A concurrent {@code applyConfig} that arrives
     * BEFORE that capture is observed by the run about to start; one that
     * arrives AFTER the capture is not observed until the next scheduled run.
     * The exact interleaving is not guaranteed — but it is benign: either
     * retention window is a valid delete horizon, and the next fully scheduled
     * run picks up the new cutoff cleanly. No mid-loop cutoff drift can happen
     * because {@code cutoff} is a local {@code long}.
     *
     * @param cfg reloaded config; must not be {@code null}
     * @return {@code true} if the schedule was actually changed (nothing was
     *         a-no-op)
     * @throws IllegalArgumentException if {@code cfg.purge().autoPurgeTime()}
     *         is not a valid {@code HH:mm} value
     * @since 1.3.2 Y3
     */
    public synchronized boolean applyConfig(GuardianConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        long newRetention = cfg.purge().autoPurgeSeconds();
        String hhmm = cfg.purge().autoPurgeTime();
        LocalTime newRunTime;
        try {
            newRunTime = LocalTime.parse(hhmm);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "purge.autoPurgeTime is not a valid HH:mm value: " + hhmm, e);
        }
        boolean newEnabled = newRetention > 0L;

        boolean sameRetention = newRetention == this.retentionSeconds;
        boolean sameTime = newRunTime.equals(this.runTime);
        boolean sameEnabled = newEnabled == this.enabled;
        if (sameRetention && sameTime && sameEnabled) {
            return false;
        }

        // Update state under the sync monitor so a concurrent start()/shutdown()
        // observes the new values consistently.
        this.retentionSeconds = newRetention;
        this.runTime = newRunTime;
        this.enabled = newEnabled;

        if (!newEnabled) {
            // Disable path: cancel + tear down executor. Match shutdown() semantics
            // but leave stopRequested false so a subsequent applyConfig(true) can
            // re-enable.
            if (nextTask != null) {
                nextTask.cancel(false);
                nextTask = null;
            }
            if (exec != null) {
                exec.shutdown();
                exec = null;
            }
            LOG.info("AutoPurgeScheduler DISABLED via /vg reload (retention set to 0)");
            return true;
        }

        // Enabled path — either transition to enabled OR reschedule under new time.
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VonixGuardian-AutoPurge");
                t.setDaemon(true);
                return t;
            });
        }
        if (nextTask != null) {
            nextTask.cancel(false);
            nextTask = null;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        ZonedDateTime next = nextRunOf(runTime, zone, now);
        LOG.info("AutoPurgeScheduler RESCHEDULED via /vg reload (retention={}s, runAt={}, nextRun={})",
                retentionSeconds, runTime, LOG_FMT.format(next));
        scheduleAt(next);
        return true;
    }

    /** Graceful shutdown; blocks up to {@code awaitMillis} for in-flight tasks. */
    public synchronized void shutdown(long awaitMillis) {
        stopRequested = true;
        if (exec == null) {
            return;
        }
        if (nextTask != null) {
            nextTask.cancel(false);
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(awaitMillis, TimeUnit.MILLISECONDS)) {
                LOG.warn("AutoPurgeScheduler did not terminate in {}ms; forcing", awaitMillis);
                exec.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
        }
    }

    // ------------------------------------------------------------- introspection

    public boolean isEnabled() {
        return enabled;
    }

    /** Total rows the scheduler has deleted since the JVM started. */
    public long getRowsPurgedSinceRestart() {
        return totalPurgedSinceRestart.get();
    }

    /** Rows deleted on the most recent run, or {@link Optional#empty()} if none yet. */
    public Optional<Long> getLastRunDeleted() {
        long v = lastRunDeleted.get();
        return v < 0L ? Optional.empty() : Optional.of(v);
    }

    public long retentionSeconds() { return retentionSeconds; }
    public LocalTime runTime()     { return runTime; }
}
