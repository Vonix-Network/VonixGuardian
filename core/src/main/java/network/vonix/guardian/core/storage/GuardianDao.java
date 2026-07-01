package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;

import java.util.List;
import java.util.UUID;

/**
 * Persistence contract for VonixGuardian. Backed by SQLite, MySQL, or Postgres.
 *
 * <p>All blocking methods are executed off the server thread by callers (typically the
 * {@code AsyncWriteQueue} worker or a {@code /vg lookup} command worker). Implementations
 * are NOT required to be safe to call from the server tick thread.
 */
public interface GuardianDao extends AutoCloseable {

    /** Initialize schema, run migrations if needed. Blocking. */
    void init() throws Exception;

    /** Insert a batch on the calling thread (driven by AsyncWriteQueue from a worker). */
    int insertBatch(List<Action> batch) throws Exception;

    /** Synchronous query — runs on calling thread (caller is a worker, not the server thread). */
    List<Action> query(QueryFilter filter, int offset, int limit) throws Exception;

    /** Row count for the same filter — used by {@code /vg lookup #count}. */
    long count(QueryFilter filter) throws Exception;

    /** Mark a set of action IDs as rolled-back (used by RollbackEngine). */
    int markRolledBack(List<Long> ids, boolean rolledBack) throws Exception;

    /** Delete records matching filter (purge). Returns rows deleted. */
    long purge(QueryFilter filter) throws Exception;

    /**
     * Dialect-specific storage optimization for the Guardian tables
     * ({@code vg_actions}, {@code vg_rollback_batches},
     * {@code vg_rollback_batch_actions}).
     *
     * <ul>
     *   <li>MySQL / MariaDB — {@code OPTIMIZE TABLE ...}.</li>
     *   <li>PostgreSQL — {@code VACUUM ANALYZE ...} (autocommit).</li>
     *   <li>SQLite — {@code VACUUM} (whole-db; SQLite auto-optimizes).</li>
     * </ul>
     *
     * <p>Best-effort — implementations MUST NOT throw when a table is missing
     * or the dialect has no equivalent. A per-run soft cap of
     * {@code maxRuntimeMillis} is applied via {@code Statement.setQueryTimeout};
     * if the cap trips, the method returns whatever progress was made and
     * leaves the DB consistent.
     *
     * @param maxRuntimeMillis soft cap on total wall-clock time, in ms
     * @return outcome (duration, best-effort bytes freed)
     */
    OptimizeResult optimize(long maxRuntimeMillis) throws Exception;

    /**
     * Outcome of {@link #optimize(long)}.
     *
     * @param durationMillis wall-clock time the optimization took
     * @param bytesFreed     best-effort estimate of space reclaimed;
     *                       {@code -1} if the dialect cannot report it
     * @param completed      {@code true} if all statements ran to completion;
     *                       {@code false} if the runtime cap tripped or a
     *                       non-fatal error was swallowed
     */
    record OptimizeResult(long durationMillis, long bytesFreed, boolean completed) {}

    /**
     * Chunked purge helper for the auto-purge scheduler: delete up to
     * {@code chunkLimit} rows whose {@code ts < cutoffMillis}. Returns the
     * number of rows actually removed.
     *
     * <p>Implemented as a portable {@code DELETE ... WHERE id IN (SELECT id ...
     * LIMIT ?)} subquery so SQLite, MySQL, and PostgreSQL all agree.
     *
     * @param cutoffMillis exclusive upper-bound timestamp; rows strictly older are eligible
     * @param chunkLimit   maximum rows to remove in this call (&gt; 0)
     * @return rows deleted (0 when nothing older than {@code cutoffMillis} remains)
     */
    long purgeOlderThan(long cutoffMillis, int chunkLimit) throws Exception;

    /** Resolve / insert user, return user_id. */
    int resolveUser(UUID uuid, String name) throws Exception;

    /** Resolve / insert world, return world_id. */
    int resolveWorld(String key) throws Exception;

    /**
     * Open a rollback/restore batch in the audit table. The batch row + all action ids
     * are inserted in a single JDBC transaction; the batch is left with
     * {@code completed=0} until {@link #closeRollbackBatch(long)} is called.
     *
     * @param actorUuid initiating actor (nullable for console)
     * @param mode      {@code 0=ROLLBACK}, {@code 1=RESTORE}
     * @param filterJson the original filter JSON; nullable
     * @param actionIds the action ids affected by this batch
     * @return the new batch id
     * @throws Exception on DB failure (transaction rolled back)
     */
    long openRollbackBatch(UUID actorUuid, int mode, String filterJson, List<Long> actionIds) throws Exception;

    /**
     * Mark a previously opened rollback batch as completed.
     *
     * @param batchId the batch id returned by {@link #openRollbackBatch}
     * @return number of rows updated (0 if no such batch, 1 on success)
     */
    int closeRollbackBatch(long batchId) throws Exception;

    /**
     * Crash-recovery: return the action ids belonging to rollback batches that were opened
     * but never completed. Used by {@code Guardian} on startup to either complete or roll
     * back the in-flight operations from the previous process lifetime.
     *
     * @return action ids in incomplete batches, in insertion order (may be empty)
     */
    List<Long> findIncompleteBatchActionIds() throws Exception;

    /**
     * Fast-path existence probe used by the public {@code VonixGuardianAPI}
     * (W3-B12+B13). Answers "did {@code user} perform any of {@code types}
     * at exactly {@code (worldId, x, y, z)} within the last
     * {@code withinMillis} milliseconds?" using an indexed lookup and a
     * {@code LIMIT 1}.
     *
     * <p>Zero or negative {@code withinMillis} disables the temporal bound.
     * A {@code null} or empty {@code types} array matches ANY action type.
     *
     * @param user         actor UUID (non-null)
     * @param worldId      world / dimension key (non-null)
     * @param x            block X
     * @param y            block Y
     * @param z            block Z
     * @param types        action-type filter; {@code null} / empty = any
     * @param withinMillis temporal window in ms; {@code &lt;= 0} = unbounded
     * @return {@code true} iff at least one matching row exists
     */
    boolean hasActionsInWindow(UUID user, String worldId, int x, int y, int z,
                               ActionType[] types, long withinMillis) throws Exception;

    /** Health check. */
    boolean isHealthy();

    @Override
    void close();
}
