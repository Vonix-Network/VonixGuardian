package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
 *
 * <p><b>Orphan reclamation.</b> Deleting action rows used to leave the
 * {@code vg_users} / {@code vg_worlds} rows that only those actions referenced
 * behind forever: nothing in the tree ever deletes them, so a long-lived server
 * accumulates one row per player who ever joined and one per world that ever
 * existed. {@link GuardianDao#purge} now carries the obligation to reclaim them
 * inside the same transaction (it must, because it owns the resolved-id caches
 * that would otherwise point at deleted rows), and
 * {@link GuardianDao#countOrphanOwners()} reports whatever residue a backend
 * chose to keep. This engine asks for that number and states it in the result —
 * {@link #reclaimOrphansAfterDelete()} and {@link OrphanReport}. A purge NEVER
 * fails because of reclaim: the row count stays authoritative and any residue is
 * named rather than swallowed.
 */
public final class PurgeEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeEngine.class);

    /** Hard cap on {@code #optimize} wall-clock time — 5 minutes. */
    public static final long OPTIMIZE_MAX_RUNTIME_MS = 5L * 60L * 1000L;

    /** How many matching rows {@code #preview} returns. The count is still exact. */
    public static final int PREVIEW_SAMPLE_LIMIT = 8;

    /**
     * Orphan-count sentinel for "the backend could not answer". Reported as
     * {@code -1} rather than {@code 0} so an operator cannot mistake a failed
     * count for a clean database.
     */
    public static final long ORPHAN_COUNT_UNKNOWN = -1L;


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
     * <p>A real delete also reclaims {@code vg_users} / {@code vg_worlds} rows
     * that no action references any more (see
     * {@link #reclaimOrphansAfterDelete()}). Reclaim runs before optimize so the
     * compaction step covers the reclaimed pages too.
     *
     * <p>{@code #count} and {@code #preview} are dry runs. They share the same
     * age floor and the same row predicate as a real purge, and they never
     * call {@link GuardianDao#purge}, {@link GuardianDao#optimize}, or the
     * orphan delete.
     *
     * @param filter         the query filter; must carry a non-null
     *                       {@code sinceMillis} that is sufficiently old
     * @param minAgeSeconds  minimum age (seconds) the time bound must satisfy
     * @return result describing the purge outcome (and any optimize and reclaim
     *         outcome)
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
        if (since == null) {
            throw new IllegalArgumentException(
                "purge requires a time filter at least " + minAgeSeconds + "s old");
        }
        // A range's youngest deleted row is the newer edge. A single t:90d
        // stores that edge as sinceMillis. Either way it must clear the floor.
        long youngestDeleted = filter.untilMillis() == null
            ? since
            : Math.max(since, filter.untilMillis());
        long floor = System.currentTimeMillis() - (minAgeSeconds * 1000L);
        if (youngestDeleted > floor) {
            throw new IllegalArgumentException(
                "purge requires a time filter at least " + minAgeSeconds + "s old");
        }
        QueryFilter purgeFilter = retentionFilter(filter);
        boolean dryRun = filter.preview() || filter.countOnly();
        mutex.lock();
        try {
            if (dryRun) {
                long matched = dao.count(purgeFilter);
                List<Action> sample = List.of();
                PurgeResult.Mode mode = PurgeResult.Mode.COUNT;
                if (filter.preview()) {
                    sample = dao.query(purgeFilter, 0, PREVIEW_SAMPLE_LIMIT);
                    mode = PurgeResult.Mode.PREVIEW;
                }
                LOG.info("PurgeEngine: dry-run {} matched={} (no rows deleted)", mode, matched);
                return new PurgeResult(0L, minAgeSeconds, since, null, matched, sample, mode,
                    OrphanReport.notApplicable());
            }
            LOG.info("PurgeEngine: executing purge olderThanMillis={} minAgeSeconds={} optimize={}",
                since, minAgeSeconds, filter.optimize());
            long deleted = dao.purge(purgeFilter);

            OrphanReport orphans = reclaimOrphansAfterDelete();
            if (orphans.scanned() && orphans.remaining() != 0L) {
                LOG.warn("PurgeEngine: {} identity rows remain unreferenced after purge (deleted={})",
                    orphans.remaining(), deleted);
            }

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
            return new PurgeResult(deleted, minAgeSeconds, since, opt, deleted, List.of(),
                PurgeResult.Mode.DELETE, orphans);
        } finally {
            mutex.unlock();
        }
    }

    // ------------------------------------------------------------------ ORPHANS

    /**
     * Report, and where possible reclaim, the {@code vg_users} / {@code vg_worlds}
     * rows a purge left unreferenced.
     *
     * <p>{@code vg_users} and {@code vg_worlds} are populated on demand by
     * {@code resolveUserOn} / {@code resolveWorldOn} during insert, and both
     * tables are declared without a foreign key into {@code vg_actions}
     * (see {@code Schema#users} / {@code Schema#worlds}). Nothing else deletes
     * from them, so before this change every purge permanently stranded one row
     * per player and per world that was only referenced by the rows it removed.
     *
     * <h3>Two halves, one owner each</h3>
     * <p>The <em>delete</em> belongs in the DAO, inside the purge transaction,
     * because the DAO owns two things the engine cannot reach: the credentials to
     * run the statement and the in-memory {@code uuid -> user_id} /
     * {@code world key -> world_id} caches that {@code resolveUser} /
     * {@code resolveWorld} consult <em>before</em> the table. Deleting an identity
     * row whose id is still cached would make the next insert write an id with no
     * row, and {@code QueryCompiler} inner-joins both tables — so those actions
     * would be invisible to every lookup. A reclaim that hides future audit rows
     * is worse than the orphans it removes. {@link GuardianDao#purge} therefore
     * carries the reclaim obligation and {@link GuardianDao#countOrphanOwners}
     * reports whatever residue the DAO chose to keep.
     *
     * <p>The <em>reporting</em> belongs here. This method asks the DAO for the
     * residue that remains after its own purge, and turns it into something the
     * operator can read: a zero, a named count, or an explicit "could not
     * measure". It never runs a delete of its own — that would be a second writer
     * racing the DAO's transaction on the same two tables.
     *
     * <p>Called from {@link #purge} while the purge mutex is held, so two purges
     * cannot interleave their reclaim and count.
     *
     * @return what the DAO reports; never {@code null}
     */
    public OrphanReport reclaimOrphansAfterDelete() {
        long remaining;
        try {
            remaining = dao.countOrphanOwners();
        } catch (Exception ex) {
            // Never let reclaim reporting erase a successful purge.
            LOG.warn("PurgeEngine: orphan count failed: {}", ex.toString());
            return new OrphanReport(true, ORPHAN_COUNT_UNKNOWN, ORPHAN_COUNT_UNKNOWN, 0L, 0L,
                "the storage backend could not count the identity rows this purge left unused: "
                    + ex);
        }
        if (remaining < 0L) {
            return new OrphanReport(true, ORPHAN_COUNT_UNKNOWN, ORPHAN_COUNT_UNKNOWN, 0L, 0L,
                "the storage backend returned a negative identity-row count");
        }
        if (remaining > 0L) {
            String why = "the storage backend keeps identity rows that no action references and "
                + "reported them as residue instead of reclaiming them";
            LOG.warn("PurgeEngine: {} identity rows remain unreferenced after purge — {}", remaining, why);
            return new OrphanReport(true, remaining, 0L, 0L, 0L, remaining + " unreferenced rows: " + why);
        }
        return new OrphanReport(true, 0L, 0L, 0L, 0L, null);
    }

    /**
     * What a purge left behind in the identity tables.
     *
     * <p>The DAO owns the delete and therefore owns the count;
     * {@link GuardianDao#countOrphanOwners()} returns one total across both
     * {@code vg_users} and {@code vg_worlds}. Splitting that total by table here
     * would be invention, so this record carries the single number the backend
     * actually reported.
     *
     * <p>{@code -1} ({@link #UNKNOWN}) means the backend could not answer. It is
     * never coerced to {@code 0}: "no orphan rows" and "nobody measured" must not
     * be the same report.
     *
     * @param scanned   {@code true} if the backend was asked at all; {@code false}
     *                  for a dry run, which deletes nothing and strands nothing
     * @param remaining unreferenced identity rows the backend reports after this
     *                  purge, or {@link #UNKNOWN}
     * @param reason    {@code null} when {@code remaining} is zero; otherwise why
     *                  rows remain or why the count is unavailable
     */
    public record OrphanReport(boolean scanned, long remaining, String reason) {

        /** Sentinel for "the backend could not measure". */
        public static final long UNKNOWN = ORPHAN_COUNT_UNKNOWN;

        /** A dry run: nothing was deleted, so nothing was stranded. */
        public static OrphanReport notApplicable() {
            return new OrphanReport(false, 0L, null);
        }

        /** {@code true} when at least one unreferenced row is still there. */
        public boolean anyKept() {
            return remaining != 0L;
        }

        /**
         * Operator-facing lines for {@code /vg purge}, brand prefix included so a
         * renderer can append them verbatim. Empty when there is nothing true to
         * say: a dry run, or a measured zero.
         */
        public List<String> lines() {
            if (remaining == 0L) {
                return List.of();
            }
            String what = remaining == UNKNOWN
                ? "an unknown number of unused player/world rows"
                : remaining + " unused player/world row" + (remaining == 1L ? "" : "s");
            String verb = remaining == 1L ? " remains" : " remain";
            String why = reason == null ? "reason not recorded" : reason;
            return List.of("[VonixGuardian] " + what + verb + " after this purge: " + why + ".");
        }
    }

    /**
     * Turn a lookup-shaped time filter into the rows a purge would remove.
     * A single {@code t:90d} is stored as a lower bound; purge deletes rows
     * at or before that instant. A two-sided {@code t:40d-60d} keeps both
     * edges and deletes only the rows inside the window.
     */
    static QueryFilter retentionFilter(QueryFilter base) {
        Long since = base.sinceMillis();
        Long until = base.untilMillis();
        if (since != null && until != null) {
            long older = Math.min(since, until);
            long newer = Math.max(since, until);
            return copyTime(base, older, newer);
        }
        return copyTime(base, null, since);
    }

    private static QueryFilter copyTime(QueryFilter base, Long sinceMillis, Long untilMillis) {
        return new QueryFilter(
            base.users(), sinceMillis, untilMillis, base.radius(), base.worldSel(),
            base.centerX(), base.centerY(), base.centerZ(),
            base.actions(), base.include(), base.exclude(),
            base.rolledBack(), false, false, base.verbose(),
            base.silent(), false, base.worldEditPlayer(), base.actionIds()
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
     * @param deletedCount       rows removed from {@code vg_actions}; {@code 0} on a dry run
     * @param minAgeSeconds      minimum age (seconds) enforced by the caller
     * @param requestedSinceMs   the {@code sinceMillis} that was accepted
     * @param optimize           optimization outcome, or {@code null} if
     *                           {@code #optimize} was not requested or the
     *                           optimize call itself threw (see log)
     * @param matchedCount       rows the predicate would remove; equals
     *                           {@code deletedCount} after a real delete
     * @param sample             rows a preview would show; empty for count and delete
     * @param mode               whether this call deleted, counted, or previewed
     * @param orphans            identity-table reclaim outcome; never {@code null}
     */
    public record PurgeResult(long deletedCount,
                              long minAgeSeconds,
                              long requestedSinceMs,
                              GuardianDao.OptimizeResult optimize,
                              long matchedCount,
                              List<Action> sample,
                              Mode mode,
                              OrphanReport orphans) {

        public PurgeResult {
            sample = sample == null ? List.of() : List.copyOf(sample);
            if (mode == null) mode = Mode.DELETE;
            if (orphans == null) orphans = OrphanReport.notApplicable();
        }

        /**
         * Compatibility constructor for a result that predates identity
         * reclamation; carries {@link OrphanReport#notApplicable()}.
         */
        public PurgeResult(long deletedCount,
                           long minAgeSeconds,
                           long requestedSinceMs,
                           GuardianDao.OptimizeResult optimize,
                           long matchedCount,
                           List<Action> sample,
                           Mode mode) {
            this(deletedCount, minAgeSeconds, requestedSinceMs, optimize, matchedCount, sample,
                mode, OrphanReport.notApplicable());
        }

        /** Compatibility constructor for a completed delete. */
        public PurgeResult(long deletedCount,
                           long minAgeSeconds,
                           long requestedSinceMs,
                           GuardianDao.OptimizeResult optimize) {
            this(deletedCount, minAgeSeconds, requestedSinceMs, optimize,
                deletedCount, List.of(), Mode.DELETE, OrphanReport.notApplicable());
        }

        /** True if the purge asked for and successfully executed the optimize step. */
        public boolean optimized() {
            return optimize != null;
        }

        /** True when no rows were deleted ({@code #count} or {@code #preview}). */
        public boolean dryRun() {
            return mode != Mode.DELETE;
        }

        public enum Mode { DELETE, COUNT, PREVIEW }
    }
}
