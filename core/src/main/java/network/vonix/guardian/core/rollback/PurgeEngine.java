package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.dbmigrate.RawJdbcAccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * behind forever: nothing ever deletes them, so a long-lived server accumulates
 * one row per player who ever joined and one per world that ever existed.
 * A real purge now reclaims them in the same operation and reports exactly what
 * it removed. See {@link #reclaimOrphansAfterDelete()} for the one condition
 * under which reclaim is refused, and {@link OrphanReport} for the shape of
 * that report. A purge NEVER fails because reclaim could not run — the row
 * count stays authoritative and the refusal is named in the result.
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

    /**
     * Most identity rows one purge reclaims. Bounded so a purge cannot hold a
     * write lock on {@code vg_users} / {@code vg_worlds} for an unbounded time on
     * a database that accumulated years of them. Anything above the bound stays
     * put, stays counted, and is named in the report; the next purge takes the
     * next batch.
     */
    public static final int MAX_RECLAIM_PER_PURGE = 10_000;

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
            if (orphans.scanned()) {
                LOG.info("PurgeEngine: orphan reclaim users={}/{} worlds={}/{} keptReason={}",
                    orphans.usersReclaimed(), orphans.usersBefore(),
                    orphans.worldsReclaimed(), orphans.worldsBefore(),
                    orphans.keptReason());
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
     * Reclaim {@code vg_users} and {@code vg_worlds} rows that no {@code vg_actions}
     * row references any more.
     *
     * <p>{@code vg_users} and {@code vg_worlds} are populated on demand by
     * {@code resolveUserOn} / {@code resolveWorldOn} during insert, and both
     * tables are declared without a foreign key into {@code vg_actions}
     * (see {@code Schema#users} / {@code Schema#worlds}). Nothing else deletes
     * from them, so every purge permanently stranded the rows for players and
     * worlds that were only ever referenced by the deleted actions.
     *
     * <h3>One gate: {@link ResolvedIdCacheControl}</h3>
     * <p>The JDBC DAO caches {@code uuid -> user_id} and {@code world key -> world_id}
     * after the first resolve and checks that cache <em>before</em> the table on
     * every later insert. Deleting a user row whose id is still cached would make
     * the next resolve hand back an id with no row; the insert would succeed
     * (there is no foreign key) and the resulting action would be invisible to
     * every lookup, because {@code QueryCompiler} inner-joins {@code vg_users}
     * and {@code vg_worlds}. A reclaim that hides future audit rows is worse than
     * the orphans it removes.
     *
     * <p>So this method refuses to touch the identity tables at all — no count,
     * no delete — unless the DAO implements {@link ResolvedIdCacheControl} and
     * therefore promises it can drop those caches. It also requires
     * {@link RawJdbcAccess}, which is how the two statements reach the backend
     * without adding methods to {@code GuardianDao}. When either is missing the
     * rows are left in place and {@link OrphanReport#keptReason()} states why; a
     * purge never fails because of this.
     *
     * <h3>Concurrency</h3>
     * <p>Called from {@link #purge} while the purge mutex is held, so two purges
     * cannot reclaim at once. Caches are invalidated before the delete (to prove
     * control a second time, after the {@code instanceof} check) and again after
     * it (to drop anything a concurrent writer resolved in between). A writer that
     * resolves and inserts inside that window can still record one invisible row;
     * the second invalidation bounds the window and the log names it.
     *
     * <p>Public so a future chunked auto-purge path can reclaim after its final
     * chunk without re-entering {@link #purge}.
     *
     * @return what was found and what was removed; never {@code null}
     */
    public OrphanReport reclaimOrphansAfterDelete() {
        if (!(dao instanceof ResolvedIdCacheControl caches)) {
            return OrphanReport.refused(
                "storage backend cannot invalidate its resolved user/world id caches; deleting "
                    + "these rows would leave cached ids pointing at no row and hide future "
                    + "actions from lookups");
        }
        if (!(dao instanceof RawJdbcAccess raw)) {
            return OrphanReport.refused(
                "storage backend exposes no JDBC access, so unused identity rows cannot be "
                    + "counted or reclaimed");
        }
        try {
            return raw.withRawConnection(c -> {
                long usersBefore = countOrphans(c, "vg_users", "user_id");
                long worldsBefore = countOrphans(c, "vg_worlds", "world_id");
                if (usersBefore == 0L && worldsBefore == 0L) {
                    return new OrphanReport(true, 0L, 0L, 0L, 0L, null);
                }
                // Invalidate before the delete as well as after: a delete that
                // throws must not leave the process holding ids for rows it did
                // remove.
                caches.invalidateResolvedIdCaches();
                long users = deleteByIds(c, "vg_users",
                    selectOrphanIds(c, "vg_users", "user_id", MAX_RECLAIM_PER_PURGE));
                long worlds = deleteByIds(c, "vg_worlds",
                    selectOrphanIds(c, "vg_worlds", "world_id", MAX_RECLAIM_PER_PURGE));
                // Second pass: drop ids resolved while this delete was running.
                caches.invalidateResolvedIdCaches();
                return new OrphanReport(true, usersBefore, worldsBefore, users, worlds, null);
            });
        } catch (Exception ex) {
            // Never let reclaim failure erase a successful purge.
            LOG.warn("PurgeEngine: orphan reclaim failed: {}", ex.toString());
            return new OrphanReport(true, ORPHAN_COUNT_UNKNOWN, ORPHAN_COUNT_UNKNOWN, 0L, 0L,
                "orphan reclaim failed: " + ex);
        }
    }

    /**
     * Orphan id discovery: a {@code NOT EXISTS} anti-join, not {@code NOT IN}.
     *
     * <p>Two reasons. <b>Cost:</b> {@code NOT IN (SELECT user_id FROM vg_actions)}
     * materialises the whole referenced-id set — on a large {@code vg_actions}
     * that is the most expensive thing the purge would run. {@code NOT EXISTS}
     * instead walks the small identity table and probes {@code vg_actions} per
     * candidate, which {@code vg_actions_user_t(user_id, ts)} and
     * {@code vg_actions_pos(world_id, x, z, y, ts)} can each serve off their
     * leading column. <b>Correctness:</b> {@code NOT IN} yields no rows at all
     * if its subquery returns a single {@code NULL}, which would silently
     * reclaim nothing; {@code NOT EXISTS} is NULL-safe.
     */
    private static long countOrphans(Connection c, String table, String fkColumn)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " u WHERE NOT EXISTS ("
            + "SELECT 1 FROM vg_actions a WHERE a." + fkColumn + " = u.id)";
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * Ids of up to {@code limit} orphan rows. Bounded so one purge cannot lock
     * the identity table for an unbounded time on a database that accumulated
     * years of them; the remainder stays visible in {@link OrphanReport}.
     */
    private static List<Long> selectOrphanIds(Connection c, String table, String fkColumn,
                                              int limit) throws SQLException {
        String sql = "SELECT u.id FROM " + table + " u WHERE NOT EXISTS ("
            + "SELECT 1 FROM vg_actions a WHERE a." + fkColumn + " = u.id)"
            + " LIMIT " + limit;
        List<Long> ids = new java.util.ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    /**
     * Delete by explicit id list rather than re-running the anti-join as a
     * {@code DELETE}. Keeps the statement portable across all three dialects
     * (no target-table alias, no correlated target reference) and guarantees the
     * delete removes exactly the ids that were just counted.
     */
    private static long deleteByIds(Connection c, String table, List<Long> ids)
            throws SQLException {
        if (ids.isEmpty()) {
            return 0L;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(table).append(" WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            return ps.executeUpdate();
        }
    }

    /**
     * Implemented by a {@link GuardianDao} that can drop the in-memory
     * {@code uuid -> user_id} / {@code world -> world_id} caches it uses to
     * short-circuit {@code resolveUser} and {@code resolveWorld}.
     *
     * <p>{@link #reclaimOrphansAfterDelete()} will not delete an orphan row
     * unless the DAO implements this, because a cached id whose row is gone
     * produces action rows that no lookup can see.
     *
     * <p>{@code AbstractJdbcDao} holds those caches in private fields today and
     * does not implement this interface, so reclaim currently reports counts and
     * removes nothing on every shipping backend. Implementing it there is a
     * one-method change: clear {@code userIdByUuid}, {@code userIdByName},
     * {@code worldIdByKey}, and {@code lastSeenLastWriteMs}.
     *
     * @since 2.3.0
     */
    public interface ResolvedIdCacheControl {
        /**
         * Drop every cached user and world identifier. Must be cheap and safe to
         * call with the purge mutex held. After this returns, the next
         * {@code resolveUser} / {@code resolveWorld} must read the table.
         */
        void invalidateResolvedIdCaches();
    }

    /**
     * What a purge found and did to the identity tables.
     *
     * <p>A {@code -1} count means the backend could not answer
     * ({@link #ORPHAN_COUNT_UNKNOWN}); it is never reported as {@code 0}.
     *
     * @param scanned        {@code true} if the tables were inspected at all;
     *                       {@code false} for a dry run, which deletes nothing
     *                       and therefore strands nothing
     * @param usersBefore    orphan {@code vg_users} rows found, or {@code -1}
     * @param worldsBefore   orphan {@code vg_worlds} rows found, or {@code -1}
     * @param usersReclaimed orphan user rows actually deleted
     * @param worldsReclaimed orphan world rows actually deleted
     * @param keptReason     {@code null} when every orphan found was removed;
     *                       otherwise why the remainder was kept
     */
    public record OrphanReport(boolean scanned,
                               long usersBefore,
                               long worldsBefore,
                               long usersReclaimed,
                               long worldsReclaimed,
                               String keptReason) {

        /** A dry run: nothing was deleted, so nothing was stranded. */
        public static OrphanReport notApplicable() {
            return new OrphanReport(false, 0L, 0L, 0L, 0L, null);
        }

        /**
         * The tables were not inspected at all, and {@code reason} says why.
         * Counts are {@link #ORPHAN_COUNT_UNKNOWN} rather than {@code 0} so the
         * report cannot read as "clean database" when nothing was measured.
         */
        public static OrphanReport refused(String reason) {
            return new OrphanReport(false, ORPHAN_COUNT_UNKNOWN, ORPHAN_COUNT_UNKNOWN,
                0L, 0L, reason);
        }

        /** Orphan users still present after this purge. */
        public long usersKept() {
            if (usersBefore < 0L) return ORPHAN_COUNT_UNKNOWN;
            return Math.max(0L, usersBefore - usersReclaimed);
        }

        /** Orphan worlds still present after this purge. */
        public long worldsKept() {
            if (worldsBefore < 0L) return ORPHAN_COUNT_UNKNOWN;
            return Math.max(0L, worldsBefore - worldsReclaimed);
        }

        /** {@code true} when rows were found and at least one was left behind. */
        public boolean anyKept() {
            return usersKept() != 0L || worldsKept() != 0L;
        }

        /** {@code true} when this purge removed an orphan row. */
        public boolean reclaimedAny() {
            return usersReclaimed > 0L || worldsReclaimed > 0L;
        }

        /**
         * Operator-facing lines for {@code /vg purge}, embedded brand prefix
         * included so a renderer can append them verbatim. Empty when the
         * tables were not inspected and no reason was recorded (a dry run).
         *
         * <p>A kept row is never silently dropped from the output: an operator
         * who runs a purge and still sees unused identity rows is told why.
         */
        public List<String> lines() {
            if (!scanned && keptReason == null) {
                return List.of();
            }
            List<String> out = new java.util.ArrayList<>(2);
            if (reclaimedAny()) {
                out.add("[VonixGuardian] Purge reclaimed "
                    + rows(usersReclaimed, "unused player row")
                    + " and " + rows(worldsReclaimed, "unused world row") + ".");
            }
            if (anyKept() || keptReason != null) {
                String why = keptReason == null ? "reason not recorded" : keptReason;
                out.add("[VonixGuardian] " + rows(usersKept(), "unused player row")
                    + " and " + rows(worldsKept(), "unused world row")
                    + " remain: " + why + ".");
            }
            return List.copyOf(out);
        }

        private static String rows(long n, String noun) {
            if (n == ORPHAN_COUNT_UNKNOWN) {
                return "an unknown number of " + noun + "s";
            }
            return n + " " + noun + (n == 1L ? "" : "s");
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
