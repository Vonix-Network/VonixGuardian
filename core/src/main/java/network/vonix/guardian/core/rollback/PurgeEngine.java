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
 * </ul>
 *
 * <p>The engine itself is source-agnostic; callers (the brigadier command
 * handler) pick the correct {@code minAgeSeconds} from
 * {@code GuardianConfig.Purge} based on who issued the command and pass it in.
 */
public final class PurgeEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeEngine.class);

    private final GuardianDao dao;

    public PurgeEngine(GuardianDao dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    /**
     * Delete actions matching {@code filter} provided the filter's
     * {@code sinceMillis} is at least {@code minAgeSeconds} in the past.
     *
     * @param filter         the query filter; must carry a non-null
     *                       {@code sinceMillis} that is sufficiently old
     * @param minAgeSeconds  minimum age (seconds) the time bound must satisfy
     * @return result describing the purge outcome
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
        LOG.info("PurgeEngine: executing purge sinceMillis={} minAgeSeconds={}", since, minAgeSeconds);
        long deleted = dao.purge(filter);
        return new PurgeResult(deleted, minAgeSeconds, since);
    }

    /**
     * Outcome of a {@link #purge} call.
     *
     * @param deletedCount       rows removed from {@code vg_actions}
     * @param minAgeSeconds      minimum age (seconds) enforced by the caller
     * @param requestedSinceMs   the {@code sinceMillis} that was accepted
     */
    public record PurgeResult(long deletedCount, long minAgeSeconds, long requestedSinceMs) {}
}
