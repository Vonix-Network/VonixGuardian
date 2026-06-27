package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
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

    /** Resolve / insert user, return user_id. */
    int resolveUser(UUID uuid, String name) throws Exception;

    /** Resolve / insert world, return world_id. */
    int resolveWorld(String key) throws Exception;

    /** Health check. */
    boolean isHealthy();

    @Override
    void close();
}
