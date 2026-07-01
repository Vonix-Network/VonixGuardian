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
    private final long retentionSeconds;
    private final LocalTime runTime;
    private final ZoneId zone;
    private final Clock clock;
    private final boolean enabled;

    private final AtomicLong totalPurgedSinceRestart = new AtomicLong();
    private final AtomicLong lastRunDeleted = new AtomicLong(-1L);
    private volatile boolean stopRequested = false;

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> nextTask;

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
        if (exec != null) {
            LOG.warn("AutoPurgeScheduler.start() called twice; ignoring");
            return;
        }
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
    }

    private void runScheduled() {
        try {
            runNow();
        } catch (Throwable t) {
            LOG.error("AutoPurgeScheduler run threw", t);
        } finally {
            // Always reschedule for tomorrow — even after a skip or error.
            if (exec != null && !exec.isShutdown()) {
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
