package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Guarded {@code /vg purge} entry point.
 *
 * <p>CoreProtect parity (see {@code SHARED-CONTRACTS.md} § 9):
 * <ul>
 *   <li>From the console: minimum age defaults to {@code 86_400} seconds (1 day).</li>
 *   <li>From in-game: minimum age defaults to {@code 2_592_000} seconds (30 days).</li>
 *   <li>{@code #optimize} on the filter triggers dialect-specific storage
 *       optimization after a successful purge — MySQL/MariaDB {@code OPTIMIZE
 *       TABLE}, PostgreSQL {@code VACUUM ANALYZE}, SQLite {@code VACUUM}.
 *       Failure to optimize does NOT fail the purge.</li>
 * </ul>
 *
 * <p>The engine itself is source-agnostic; callers (the brigadier command
 * handler) pick the correct {@code minAgeSeconds} from
 * {@code GuardianConfig.Purge} based on who issued the command and pass it in.
 *
 * <p><b>Mutex (W3-B4):</b> {@link #mutex()} returns a process-wide
 * {@link ReentrantLock} that {@code AutoPurgeScheduler} uses to
 * (a) serialise concurrent purges and (b) safely skip the daily run when a
 * manual {@code /vg purge} is already in flight. The lock is <em>optional</em>
 * on the manual path — {@link #purge} does not acquire it itself because
 * historical callers may hold longer transactions; the auto-purge daemon
 * uses {@code tryLock()} so it never blocks the server.
 */
public final class PurgeEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeEngine.class);

    /** Hard cap on {@code #optimize} wall-clock time — 5 minutes. */
    public static final long OPTIMIZE_MAX_RUNTIME_MS = 5L * 60L * 1000L;

    private final GuardianDao dao;
    private final ReentrantLock mutex = new ReentrantLock();

    public PurgeEngine(GuardianDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    /**
     * Delete actions matching {@code filter} that are older than the requested
     * {@code t:<age>} bound, provided that bound satisfies the configured
     * minimum age floor.
     *
     * <p>CoreProtect purge semantics are age-retention semantics: {@code t:30d}
     * deletes rows older than 30 days, not rows newer than 30 days. The regular
     * lookup parser represents {@code t:30d} as {@link QueryFilter#sinceMillis()},
     * so this method validates that lower lookup bound and then converts it into
     * a purge-only upper bound before calling the DAO.</p>
     *
     * <p>If {@link QueryFilter#optimize()} is {@code true}, a best-effort
     * {@code OPTIMIZE TABLE} / {@code VACUUM ANALYZE} / {@code VACUUM} runs
     * after the delete. The optimize step is bounded by
     * {@link #OPTIMIZE_MAX_RUNTIME_MS} and any failure is logged and swallowed
     * — the purge's row-count result is authoritative regardless.
     *
     * @param filter         the query filter; must carry a non-null
     *                       {@code sinceMillis} that is sufficiently old
     * @param minAgeSeconds  minimum age (seconds) the time bound must satisfy
     * @return result describing the purge outcome (and any optimize outcome)
     * @throws IllegalArgumentException if {@code filter.sinceMillis()} is
     *         {@code null} or more recent than the minimum age permits
     * @throws Exception on DAO failure
     */
    public PurgeResult purge(QueryFilter filter, long minAgeSeconds) throws Exception {
        Objects.requireNonNull(filter, "filter");
        if (minAgeSeconds < 0) {
            throw new IllegalArgumentException("minAgeSeconds must be >= 0");
        }
        Long since = filter.sinceMillis();
        long cutoff = System.currentTimeMillis() - (minAgeSeconds * 1000L);
        if (since == null || since > cutoff) {
            throw new IllegalArgumentException(
                "purge requires a time filter at least " + minAgeSeconds + "s old");
        }
        QueryFilter purgeFilter = olderThan(filter, since);
        mutex.lock();
        try {
            LOG.info("PurgeEngine: executing purge olderThanMillis={} minAgeSeconds={} optimize={}",
                since, minAgeSeconds, filter.optimize());
            long deleted = dao.purge(purgeFilter);

            GuardianDao.OptimizeResult opt = null;
            if (filter.optimize()) {
                try {
                    LOG.info("PurgeEngine: #optimize requested — running storage optimize (cap={}ms)",
                        OPTIMIZE_MAX_RUNTIME_MS);
                    opt = dao.optimize(OPTIMIZE_MAX_RUNTIME_MS);
                    if (opt.completed()) {
                        LOG.info("PurgeEngine: optimize took {}ms, reclaimed {} bytes",
                            opt.durationMillis(), opt.bytesFreed());
                    } else {
                        LOG.warn("PurgeEngine: optimize did not complete within cap — {}ms elapsed, "
                            + "bytesFreed={}", opt.durationMillis(), opt.bytesFreed());
                    }
                } catch (Exception ex) {
                    // Never let optimize failure erase a successful purge.
                    LOG.warn("PurgeEngine: optimize failed post-purge (deleted={}): {}",
                        deleted, ex.toString());
                }
            }
            return new PurgeResult(deleted, minAgeSeconds, since, opt);
        } finally {
            mutex.unlock();
        }
    }

    private static QueryFilter olderThan(QueryFilter base, long olderThanMillis) {
        return new QueryFilter(
            base.users(), null, olderThanMillis, base.radius(), base.worldSel(),
            base.centerX(), base.centerY(), base.centerZ(),
            base.actions(), base.include(), base.exclude(),
            base.rolledBack(), base.countOnly(), base.preview(), base.verbose(),
            base.silent(), base.optimize(), base.worldEditPlayer(), base.actionIds()
        );
    }

    /** DAO handle (package-visibility for {@code AutoPurgeScheduler}). */
    public GuardianDao dao() {
        return dao;
    }

    /**
     * Process-wide purge mutex. Callers on the auto-purge path use
     * {@link ReentrantLock#tryLock()} to skip a scheduled run whenever a
     * manual purge is in flight; the manual path in {@link #purge} acquires
     * it as a plain {@code lock()}.
     *
     * @return the shared purge lock; never {@code null}
     */
    public ReentrantLock mutex() {
        return mutex;
    }

    /**
     * Outcome of a {@link #purge} call.
     *
     * @param deletedCount       rows removed from {@code vg_actions}
     * @param minAgeSeconds      minimum age (seconds) enforced by the caller
     * @param requestedSinceMs   the {@code sinceMillis} that was accepted
     * @param optimize           optimization outcome, or {@code null} if
     *                           {@code #optimize} was not requested or the
     *                           optimize call itself threw (see log)
     */
    public record PurgeResult(long deletedCount,
                              long minAgeSeconds,
                              long requestedSinceMs,
                              GuardianDao.OptimizeResult optimize) {

        /** True if the purge asked for and successfully executed the optimize step. */
        public boolean optimized() {
            return optimize != null;
        }
    }
}
