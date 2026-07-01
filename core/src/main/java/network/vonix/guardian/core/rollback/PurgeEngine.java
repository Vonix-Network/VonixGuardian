package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

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
 */
public final class PurgeEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeEngine.class);

    /** Hard cap on {@code #optimize} wall-clock time — 5 minutes. */
    public static final long OPTIMIZE_MAX_RUNTIME_MS = 5L * 60L * 1000L;

    private final GuardianDao dao;

    public PurgeEngine(GuardianDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    /**
     * Delete actions matching {@code filter} provided the filter's
     * {@code sinceMillis} is at least {@code minAgeSeconds} in the past.
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
        LOG.info("PurgeEngine: executing purge sinceMillis={} minAgeSeconds={} optimize={}",
            since, minAgeSeconds, filter.optimize());
        long deleted = dao.purge(filter);

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
