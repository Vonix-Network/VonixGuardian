package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.QueryCompiler;
import network.vonix.guardian.core.storage.Schema;
import network.vonix.guardian.core.storage.dbmigrate.RawJdbcAccess;
import network.vonix.guardian.core.storage.migration.MigrationRunner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Shared JDBC implementation. Subclasses supply a {@link Connection} per call and the
 * {@link Schema.Dialect} so the right DDL is generated.
 *
 * <p>Concurrency: {@code getConnection()} returns either a pooled connection (Hikari) or a
 * single serialised connection (SQLite). The DAO contract states callers are worker
 * threads, never the server thread.
 *
 * <p>Read-side rate limit: {@link #query}, {@link #queryPageForDisplay}, and {@link #count}
 * acquire a permit from {@code lookupSemaphore} (sized at construction by
 * {@code config.lookup().maxConcurrent()}) for the duration of the call, providing
 * simple back-pressure for ad-hoc operator queries. If the semaphore is {@code null},
 * no rate limit is applied (test-only path).
 *
 * <p>Result-size cap: {@link #query} and {@link #queryPageForDisplay} clamp the
 * {@code limit} argument to {@code min(limit, maxResultRows)}. A value of {@code 0}
 * disables the cap.
 */
public abstract class AbstractJdbcDao implements GuardianDao, RawJdbcAccess {

    /** Keep the flag bind plus IDs safely below SQLite and common JDBC bind limits. */
    static final int MARK_ROLLED_BACK_CHUNK_SIZE = 500;

    private static final String INSERT_ACTION_SQL =
        "INSERT INTO vg_actions("
        + "ts, type, user_id, world_id, x, y, z, target, meta, amount, rolled_back, source_tag, "
        + "sign_side, sign_dye_color, sign_waxed, "
        + "old_block_state, new_block_state, block_entity_nbt, item_nbt, entity_nbt, inventory_slot, pair_id"
        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    /** uuid -> id, only populated for real (non-null) users; bounded admission. */
    private final ConcurrentHashMap<UUID, Integer> userIdByUuid = new ConcurrentHashMap<>();
    /**
     * Interns UUID strings read from JDBC so repeated lookup/rollback rows for
     * the same player do not re-parse {@code UUID.fromString}. Bounded so a
     * hostile unique-uuid flood cannot grow without limit.
     */
    private final ConcurrentHashMap<String, UUID> uuidIntern = new ConcurrentHashMap<>();
    private static final int UUID_INTERN_CAP = 4096;
    /** name -> id, used for sentinels and uuid-less lookups; bounded admission. */
    private final ConcurrentHashMap<String, Integer> userIdByName = new ConcurrentHashMap<>();
    private static final int USER_ID_CACHE_CAP = 4096;
    /**
     * v1.3.1 X6 (P3-8): last time (ms) we issued an {@code UPDATE vg_users SET last_seen}
     * for each user id. Amortizes the write — resolveUserOn only issues the UPDATE when
     * the in-memory record drifts by more than {@link #LAST_SEEN_DRIFT_MS}. Kept keyed
     * by user id (int) because a single user may be resolved by both uuid and name paths.
     */
    private final ConcurrentHashMap<Integer, Long> lastSeenLastWriteMs = new ConcurrentHashMap<>();
    /** Amortization threshold: only rewrite last_seen when it drifts by more than this many ms. */
    static final long LAST_SEEN_DRIFT_MS = 60_000L;
    private static final int LAST_SEEN_CACHE_CAP = 4096;
    private final ConcurrentHashMap<String, Integer> worldIdByKey = new ConcurrentHashMap<>();
    private static final int WORLD_ID_CACHE_CAP = 4096;

    /** Optional read-side rate limit. May be {@code null} (no limit). */
    private final Semaphore lookupSemaphore;

    /** Maximum time an operator lookup may wait for a saturated read permit. */
    static final long LOOKUP_PERMIT_WAIT_MS = 250L;

    /** Maximum rows materialised per {@link #query}. {@code 0} disables the cap. */
    private final int maxResultRows;

    /** Default constructor: no rate limit, no result cap. */
    protected AbstractJdbcDao() {
        this(null, 0);
    }

    /**
     * @param lookupSemaphore optional read-side rate limit; may be {@code null}
     * @param maxResultRows   absolute cap on {@code limit} passed to {@link #query};
     *                        {@code 0} disables the cap
     */
    protected AbstractJdbcDao(Semaphore lookupSemaphore, int maxResultRows) {
        this.lookupSemaphore = lookupSemaphore;
        this.maxResultRows = Math.max(0, maxResultRows);
    }

    /** Acquire a connection. Implementations decide pooling semantics. */
    protected abstract Connection borrow() throws SQLException;

    /** Release a connection (legacy best-effort path used by non-critical operations). */
    protected abstract void release(Connection c);

    /**
     * Release a connection while preserving close failures for critical mutation paths.
     * Pooled backends override this to invalidate a connection whose close operation fails.
     */
    protected void releaseChecked(Connection c) throws SQLException {
        release(c);
    }

    /** Dialect identifier. */
    protected abstract Schema.Dialect dialect();

    /** Health check — implementations probe the underlying pool/connection. */
    @Override
    public abstract boolean isHealthy();

    @Override
    public void init() throws SQLException {
        Connection c = borrow();
        try {
            // Fresh installs: create tables at CURRENT_VERSION and stamp v{CURRENT_VERSION}.
            Schema.createTables(c, dialect());
            // Existing installs stamped at an older version: walk any pending
            // in-place migrations. Idempotent — no-op if the recorded version
            // is already CURRENT_VERSION.
            MigrationRunner.defaults().migrateToCurrent(c, dialect());
        } finally {
            release(c);
        }
    }

    // ------------------------------------------------------------------ INSERT

    @Override
    public int insertBatch(List<Action> batch) throws SQLException {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        Connection c = borrow();
        boolean prevAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            try {
                int total = insertActionsOn(c, batch);
                c.commit();
                return total;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            release(c);
        }
    }

    @Override
    public int insertBatchWithOutbox(List<Action> batch, byte[] outboxPayload) throws SQLException {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        if (outboxPayload == null) {
            throw new SQLException("sink outbox payload is required");
        }
        Connection c = borrow();
        boolean prevAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            try {
                if (peekSinkOutboxOn(c) != null) {
                    c.commit();
                    return 0;
                }
                int total = insertActionsOn(c, batch);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vg_sink_outbox(id, payload, created_ts) VALUES (1, ?, ?)")) {
                    ps.setBytes(1, outboxPayload);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
                return total;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            release(c);
        }
    }

    @Override
    public byte[] peekSinkOutbox() throws SQLException {
        Connection c = borrow();
        try {
            return peekSinkOutboxOn(c);
        } finally {
            release(c);
        }
    }

    @Override
    public void ackSinkOutbox() throws SQLException {
        Connection c = borrow();
        boolean prevAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM vg_sink_outbox WHERE id = 1")) {
                ps.executeUpdate();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            release(c);
        }
    }

    @Override
    public int markRepairRequired(List<RepairRequired> rows) throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Connection c = borrow();
        boolean prevAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            int total = 0;
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM vg_repair_required WHERE action_id = ?")) {
                    for (RepairRequired row : rows) {
                        del.setLong(1, row.actionId());
                        del.addBatch();
                    }
                    del.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vg_repair_required(action_id, pair_id, batch_id, reason, ts) "
                                + "VALUES (?,?,?,?,?)")) {
                    for (RepairRequired row : rows) {
                        ps.setLong(1, row.actionId());
                        if (row.pairId() == null) {
                            ps.setNull(2, java.sql.Types.BIGINT);
                        } else {
                            ps.setLong(2, row.pairId());
                        }
                        if (row.batchId() == null) {
                            ps.setNull(3, java.sql.Types.INTEGER);
                        } else {
                            ps.setLong(3, row.batchId());
                        }
                        ps.setString(4, row.reason() == null ? "repair-required" : row.reason());
                        ps.setLong(5, row.timestamp());
                        ps.addBatch();
                    }
                    int[] r = ps.executeBatch();
                    for (int v : r) {
                        if (v >= 0) total += v;
                        else total += 1;
                    }
                }
                c.commit();
                return total;
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            release(c);
        }
    }

    @Override
    public List<RepairRequired> findRepairRequired() throws SQLException {
        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT action_id, pair_id, batch_id, reason, ts FROM vg_repair_required ORDER BY ts, action_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<RepairRequired> out = new ArrayList<>();
                while (rs.next()) {
                    long pairRaw = rs.getLong(2);
                    Long pairId = rs.wasNull() ? null : pairRaw;
                    long batchRaw = rs.getLong(3);
                    Long batchId = rs.wasNull() ? null : batchRaw;
                    out.add(new RepairRequired(rs.getLong(1), pairId, batchId, rs.getString(4), rs.getLong(5)));
                }
                return out;
            }
        } finally {
            release(c);
        }
    }

    private static byte[] peekSinkOutboxOn(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM vg_sink_outbox WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getBytes(1);
            }
        }
    }

    private int insertActionsOn(Connection c, List<Action> batch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_ACTION_SQL)) {
            for (Action a : batch) {
                int userId = resolveUserOn(c, a.actorUuid(), nullSafeName(a));
                int worldId = resolveWorldOn(c, a.worldId());
                ps.setLong(1, a.timestamp());
                ps.setShort(2, (short) a.type().id());
                ps.setInt(3, userId);
                ps.setInt(4, worldId);
                ps.setInt(5, a.x());
                ps.setInt(6, a.y());
                ps.setInt(7, a.z());
                ps.setString(8, a.targetId() == null ? "" : a.targetId());
                if (a.targetMeta() == null) {
                    ps.setNull(9, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(9, a.targetMeta());
                }
                ps.setInt(10, a.amount());
                ps.setInt(11, a.rolledBack() ? 1 : 0);
                if (a.sourceTag() == null) {
                    ps.setNull(12, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(12, a.sourceTag());
                }
                if (a.signSide() == null) {
                    ps.setNull(13, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(13, a.signSide());
                }
                if (a.signDyeColor() == null) {
                    ps.setNull(14, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(14, a.signDyeColor());
                }
                if (a.signWaxed() == null) {
                    ps.setNull(15, java.sql.Types.BOOLEAN);
                } else {
                    ps.setBoolean(15, a.signWaxed());
                }
                if (a.oldBlockState() == null) {
                    ps.setNull(16, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(16, a.oldBlockState());
                }
                if (a.newBlockState() == null) {
                    ps.setNull(17, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(17, a.newBlockState());
                }
                if (a.blockEntityNbt() == null) {
                    ps.setNull(18, java.sql.Types.VARBINARY);
                } else {
                    ps.setBytes(18, a.blockEntityNbt());
                }
                if (a.itemNbt() == null) {
                    ps.setNull(19, java.sql.Types.VARBINARY);
                } else {
                    ps.setBytes(19, a.itemNbt());
                }
                if (a.entityNbt() == null) {
                    ps.setNull(20, java.sql.Types.VARBINARY);
                } else {
                    ps.setBytes(20, a.entityNbt());
                }
                if (a.inventorySlot() == null) {
                    ps.setNull(21, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(21, a.inventorySlot());
                }
                if (a.pairId() == null || a.pairId() == 0L) {
                    ps.setNull(22, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(22, a.pairId());
                }
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            int total = 0;
            for (int v : r) {
                if (v >= 0) total += v;
                else total += 1; // SUCCESS_NO_INFO
            }
            return total;
        }
    }

    private static String nullSafeName(Action a) {
        return a.actorName() != null ? a.actorName() : "#unknown";
    }

    // ------------------------------------------------------------------ SELECT

    @Override
    public List<Action> query(QueryFilter filter, int offset, int limit) throws SQLException {
        return query(filter, offset, limit, true, null);
    }

    @Override
    public GuardianDao.QueryPage queryPage(QueryFilter filter, int offset, int limit) throws SQLException {
        return toQueryPage(query(filter, offset, limit, true, null), limit);
    }

    @Override
    public GuardianDao.QueryPage queryPageForDisplay(QueryFilter filter, int offset, int limit)
            throws SQLException {
        return toQueryPage(query(filter, offset, limit, false, null), limit);
    }

    @Override
    public GuardianDao.QueryPage queryPageAfter(QueryFilter filter, long afterTs, long afterId, int limit)
            throws SQLException {
        QueryCompiler.Seek after = new QueryCompiler.Seek(afterTs, afterId);
        return toQueryPage(query(filter, 0, limit, true, after), limit);
    }

    @Override
    public GuardianDao.QueryPage queryPageForDisplayAfter(QueryFilter filter, long afterTs, long afterId,
                                                          int limit) throws SQLException {
        QueryCompiler.Seek after = new QueryCompiler.Seek(afterTs, afterId);
        return toQueryPage(query(filter, 0, limit, false, after), limit);
    }

    private List<Action> query(QueryFilter filter, int offset, int limit, boolean includePayload,
                               QueryCompiler.Seek after) throws SQLException {
        int effectiveLimit = (maxResultRows > 0) ? Math.min(limit, maxResultRows) : limit;
        acquireLookupPermit();
        try {
            QueryCompiler.Compiled q;
            if (after != null) {
                q = includePayload
                        ? QueryCompiler.compileSelectAfter(filter, after, effectiveLimit)
                        : QueryCompiler.compileSelectForDisplayAfter(filter, after, effectiveLimit);
            } else {
                q = includePayload
                        ? QueryCompiler.compileSelect(filter, offset, effectiveLimit)
                        : QueryCompiler.compileSelectForDisplay(filter, offset, effectiveLimit);
            }
            Connection c = borrow();
            try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                bind(ps, q.binds());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Action> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(readAction(rs, includePayload));
                    }
                    return out;
                }
            } finally {
                release(c);
            }
        } finally {
            releaseLookupPermit();
        }
    }

    private GuardianDao.QueryPage toQueryPage(List<Action> rows, int requestedLimit) {
        // query() silently clamps to maxResultRows. A shortened non-empty page is
        // only ambiguous when the caller asked for more than the cap AND the
        // response filled that cap — fewer rows means true EOF. Fail closed on
        // the ambiguous full-cap case so planners cannot treat it as completion.
        boolean truncated = maxResultRows > 0
                && requestedLimit > maxResultRows
                && rows.size() == maxResultRows;
        return new GuardianDao.QueryPage(rows, truncated);
    }

    @Override
    public long count(QueryFilter filter) throws SQLException {
        acquireLookupPermit();
        try {
            QueryCompiler.Compiled q = QueryCompiler.compileCount(filter);
            Connection c = borrow();
            try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                bind(ps, q.binds());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } finally {
                release(c);
            }
        } finally {
            releaseLookupPermit();
        }
    }

    private void acquireLookupPermit() {
        if (lookupSemaphore == null) return;
        try {
            if (!lookupSemaphore.tryAcquire(LOOKUP_PERMIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new LookupBusyException(
                        "lookup capacity is busy; retry after current lookups complete");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LookupBusyException(
                    "lookup capacity wait interrupted; retry the lookup", ex);
        }
    }

    private void releaseLookupPermit() {
        if (lookupSemaphore != null) {
            lookupSemaphore.release();
        }
    }

    private Action readAction(ResultSet rs, boolean includePayload) throws SQLException {
        long id = rs.getLong(1);
        long ts = rs.getLong(2);
        ActionType type = ActionType.byId(rs.getInt(3));
        String uuidStr = rs.getString(4);
        UUID uuid = uuidStr == null ? null : safeUuid(uuidStr);
        String name = rs.getString(5);
        String worldKey = rs.getString(6);
        int x = rs.getInt(7), y = rs.getInt(8), z = rs.getInt(9);
        String target = rs.getString(10);
        String meta = rs.getString(11);
        int amount = rs.getInt(12);
        boolean rolledBack = rs.getInt(13) != 0;
        String sourceTag = rs.getString(14);
        String signSide = rs.getString(15);
        String signDyeColor = rs.getString(16);
        boolean waxedRaw = rs.getBoolean(17);
        Boolean signWaxed = rs.wasNull() ? null : waxedRaw;
        if (!includePayload) {
            return new Action(id, ts, type, uuid, name, worldKey, x, y, z, target, meta, amount,
                              rolledBack, sourceTag, signSide, signDyeColor, signWaxed,
                              null, null, null, null, null, readPairId(rs, 19), readInventorySlot(rs, 18));
        }
        // v1.3.1 X1 NBT columns. Read regardless of storage.persistNbt so
        // historical rows survive the operator toggling the flag back off.
        String oldBlockState = rs.getString(18);
        String newBlockState = rs.getString(19);
        byte[] blockEntityNbt = rs.getBytes(20);
        byte[] itemNbt = rs.getBytes(21);
        byte[] entityNbt = rs.getBytes(22);
        return new Action(id, ts, type, uuid, name, worldKey, x, y, z, target, meta, amount,
                          rolledBack, sourceTag, signSide, signDyeColor, signWaxed,
                          oldBlockState, newBlockState, blockEntityNbt, itemNbt, entityNbt,
                          readPairId(rs, 24), readInventorySlot(rs, 23));
    }

    private static Integer readInventorySlot(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long readPairId(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        if (rs.wasNull() || v == 0L) {
            return null;
        }
        return v;
    }

    @Override
    public List<Action> findByPairIds(java.util.Collection<Long> pairIds) throws SQLException {
        if (pairIds == null || pairIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : pairIds) {
            if (id != null && id != 0L) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Action> out = new ArrayList<>();
        Connection c = borrow();
        try {
            for (int start = 0; start < ids.size(); start += MARK_ROLLED_BACK_CHUNK_SIZE) {
                int end = Math.min(start + MARK_ROLLED_BACK_CHUNK_SIZE, ids.size());
                StringBuilder sql = new StringBuilder("SELECT ")
                        .append(QueryCompiler.SELECT_PROJECTION)
                        .append(" FROM vg_actions a ")
                        .append("JOIN vg_users  u ON u.id = a.user_id ")
                        .append("JOIN vg_worlds w ON w.id = a.world_id ")
                        .append("WHERE a.pair_id IN (");
                for (int i = start; i < end; i++) {
                    if (i > start) sql.append(',');
                    sql.append('?');
                }
                sql.append(')');
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    for (int i = start; i < end; i++) {
                        ps.setLong(i - start + 1, ids.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(readAction(rs, true));
                        }
                    }
                }
            }
            return out;
        } finally {
            release(c);
        }
    }

    static <K, V> void cacheIfWithinBound(
            ConcurrentHashMap<K, V> cache, K key, V value, int cap) {
        if (cache == null || key == null || value == null || cap <= 0) {
            return;
        }
        synchronized (cache) {
            if (cache.containsKey(key) || cache.size() < cap) {
                cache.put(key, value);
            }
        }
    }

    private UUID safeUuid(String s) {
        UUID interned = uuidIntern.get(s);
        if (interned != null) {
            return interned;
        }
        try {
            UUID parsed = UUID.fromString(s);
            synchronized (uuidIntern) {
                UUID raced = uuidIntern.get(s);
                if (raced != null) {
                    return raced;
                }
                if (uuidIntern.size() < UUID_INTERN_CAP) {
                    uuidIntern.put(s, parsed);
                }
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ MUTATE

    @Override
    public int markRolledBack(List<Long> ids, boolean rolledBack) throws SQLException {
        if (ids == null || ids.isEmpty()) return 0;
        Connection c = borrow();
        boolean previousAutoCommit = true;
        boolean autoCommitKnown = false;
        Throwable primaryFailure = null;
        Throwable cleanupFailure = null;
        int updated = 0;
        try {
            previousAutoCommit = c.getAutoCommit();
            autoCommitKnown = true;
            // Chunking is paired with one transaction so a parameter-limit
            // workaround cannot create a partially marked rollback batch.
            c.setAutoCommit(false);
            for (int start = 0; start < ids.size(); start += MARK_ROLLED_BACK_CHUNK_SIZE) {
                int end = Math.min(start + MARK_ROLLED_BACK_CHUNK_SIZE, ids.size());
                StringBuilder sb = new StringBuilder("UPDATE vg_actions SET rolled_back = ? WHERE id IN (");
                for (int i = start; i < end; i++) {
                    if (i > start) sb.append(',');
                    sb.append('?');
                }
                sb.append(')');
                try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
                    ps.setInt(1, rolledBack ? 1 : 0);
                    for (int i = start; i < end; i++) {
                        ps.setLong(i - start + 2, ids.get(i));
                    }
                    updated += ps.executeUpdate();
                }
            }
            c.commit();
        } catch (Throwable ex) {
            primaryFailure = ex;
            try {
                c.rollback();
            } catch (Throwable rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
            }
        } finally {
            if (autoCommitKnown) {
                try {
                    c.setAutoCommit(previousAutoCommit);
                } catch (Throwable restoreFailure) {
                    cleanupFailure = restoreFailure;
                    // A connection with unknown transaction state must not
                    // return to a pool or remain the live SQLite connection.
                    try {
                        c.close();
                    } catch (Throwable closeFailure) {
                        restoreFailure.addSuppressed(closeFailure);
                    }
                }
            }
            try {
                releaseChecked(c);
            } catch (Throwable releaseFailure) {
                if (cleanupFailure == null) {
                    cleanupFailure = releaseFailure;
                } else {
                    cleanupFailure.addSuppressed(releaseFailure);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure;
                } else {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (primaryFailure != null) {
            rethrowJdbcFailure(primaryFailure);
        }
        return updated;
    }

    private static void rethrowJdbcFailure(Throwable failure) throws SQLException {
        if (failure instanceof SQLException ex) throw ex;
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
        throw new SQLException("JDBC rollback update failed", failure);
    }

    @Override
    public long purge(QueryFilter filter) throws SQLException {
        QueryCompiler.Compiled q = QueryCompiler.compileDelete(filter);
        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(q.sql())) {
            bind(ps, q.binds());
            return ps.executeUpdate();
        } finally {
            release(c);
        }
    }

    // ------------------------------------------------------------------ OPTIMIZE

    /** Guardian tables affected by {@link #optimize(long)}. */
    protected static final String[] OPTIMIZE_TABLES = {
        "vg_actions", "vg_rollback_batches", "vg_rollback_batch_actions"
    };

    /**
     * Default best-effort optimization. Dispatches on {@link #dialect()}:
     * MySQL/MariaDB → {@code OPTIMIZE TABLE t1, t2, t3};
     * PostgreSQL   → {@code VACUUM ANALYZE t;} per-table (autocommit);
     * SQLite       → {@code VACUUM} (whole-db).
     *
     * <p>Runtime cap is applied per-statement via {@link Statement#setQueryTimeout(int)}
     * (seconds). If the cap trips or a statement errors, the method logs and
     * returns {@code completed=false} rather than surfacing the exception —
     * optimize is opportunistic, purge results MUST NOT be lost to a VACUUM
     * hiccup.
     */
    @Override
    public OptimizeResult optimize(long maxRuntimeMillis) throws SQLException {
        long capMs = Math.max(1L, maxRuntimeMillis);
        int perStmtTimeoutSec = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, capMs / 1000L));
        long t0 = System.nanoTime();
        long deadlineNs = t0 + capMs * 1_000_000L;

        long bytesBefore = safeSizeBytes();
        boolean completed = true;
        Schema.Dialect d = dialect();

        Connection c = borrow();
        boolean prevAutoCommit = true;
        try {
            prevAutoCommit = c.getAutoCommit();
            // Postgres VACUUM cannot run in a transaction; MySQL OPTIMIZE tolerates
            // autocommit; SQLite VACUUM requires autocommit too.
            if (!prevAutoCommit) {
                try { c.setAutoCommit(true); } catch (SQLException ignored) {}
            }

            List<String> stmts = optimizeStatementsFor(d);
            for (String sql : stmts) {
                if (System.nanoTime() > deadlineNs) {
                    org.slf4j.LoggerFactory.getLogger(AbstractJdbcDao.class)
                        .warn("optimize: runtime cap ({} ms) exceeded before '{}'", capMs, sql);
                    completed = false;
                    break;
                }
                try (Statement st = c.createStatement()) {
                    try { st.setQueryTimeout(perStmtTimeoutSec); } catch (SQLException ignored) {}
                    st.execute(sql);
                } catch (SQLException ex) {
                    // Missing-table etc. — best effort. Log and press on.
                    org.slf4j.LoggerFactory.getLogger(AbstractJdbcDao.class)
                        .warn("optimize: '{}' failed ({}); continuing", sql, ex.getMessage());
                    completed = false;
                }
            }
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            release(c);
        }

        long bytesAfter = safeSizeBytes();
        long freed = (bytesBefore >= 0 && bytesAfter >= 0) ? (bytesBefore - bytesAfter) : -1L;
        long durationMs = (System.nanoTime() - t0) / 1_000_000L;
        return new OptimizeResult(durationMs, freed, completed);
    }

    /** SQL statements to run for this dialect, in order. Overridable for tests. */
    protected List<String> optimizeStatementsFor(Schema.Dialect d) {
        switch (d) {
            case MYSQL: {
                StringBuilder sb = new StringBuilder("OPTIMIZE TABLE ");
                for (int i = 0; i < OPTIMIZE_TABLES.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(OPTIMIZE_TABLES[i]);
                }
                return List.of(sb.toString());
            }
            case POSTGRES: {
                List<String> out = new ArrayList<>(OPTIMIZE_TABLES.length);
                for (String t : OPTIMIZE_TABLES) {
                    out.add("VACUUM ANALYZE " + t);
                }
                return out;
            }
            case SQLITE:
            default:
                return List.of("VACUUM");
        }
    }

    /**
     * Best-effort byte-size probe used to compute {@code bytesFreed}. Returns
     * {@code -1} when the dialect doesn't offer a cheap answer.
     */
    protected long safeSizeBytes() {
        Schema.Dialect d;
        try { d = dialect(); } catch (RuntimeException ex) { return -1L; }
        Connection c;
        try { c = borrow(); } catch (SQLException ex) { return -1L; }
        try (Statement st = c.createStatement()) {
            String sql = switch (d) {
                case MYSQL ->
                    "SELECT COALESCE(SUM(data_length + index_length), 0) "
                  + "FROM information_schema.tables "
                  + "WHERE table_schema = DATABASE() "
                  + "AND table_name IN ('vg_actions','vg_rollback_batches','vg_rollback_batch_actions')";
                case POSTGRES ->
                    "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)), 0) "
                  + "FROM pg_class c "
                  + "WHERE c.relname IN ('vg_actions','vg_rollback_batches','vg_rollback_batch_actions')";
                case SQLITE ->
                    "SELECT page_count * page_size FROM pragma_page_count(), pragma_page_size()";
            };
            try (ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) return Math.max(0L, rs.getLong(1));
            }
            return -1L;
        } catch (SQLException ex) {
            return -1L;
        } finally {
            release(c);
        }
    }

    // ------------------------------------------------------------------ AUTO-PURGE

    @Override
    public long purgeOlderThan(long cutoffMillis, int chunkLimit) throws SQLException {
        if (chunkLimit <= 0) {
            throw new IllegalArgumentException("chunkLimit must be > 0 (got " + chunkLimit + ")");
        }
        // Portable across SQLite, MySQL, and PostgreSQL: a DELETE ... WHERE id IN (SELECT id ... LIMIT ?).
        // MySQL does not permit LIMIT directly on a DELETE that references the same table via subquery
        // without wrapping the subquery in an intermediate SELECT — the FROM-derived form below works
        // on all three backends.
        final String sql =
            "DELETE FROM vg_actions WHERE id IN ("
          + "SELECT id FROM (SELECT id FROM vg_actions WHERE ts < ? ORDER BY ts ASC LIMIT ?) AS victims"
          + ")";
        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoffMillis);
            ps.setInt(2, chunkLimit);
            return ps.executeUpdate();
        } finally {
            release(c);
        }
    }

    // ------------------------------------------------------------ ROLLBACK BATCH

    @Override
    public long openRollbackBatch(UUID actorUuid, int mode, String filterJson, List<Long> actionIds)
            throws SQLException {
        List<Long> ids = (actionIds == null) ? List.of() : actionIds;
        Connection c = borrow();
        boolean prevAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(false);
            long batchId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO vg_rollback_batches(ts, actor_uuid, mode, affected, completed, filter_json) "
                  + "VALUES (?,?,?,?,0,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, System.currentTimeMillis());
                if (actorUuid != null) ps.setString(2, actorUuid.toString());
                else ps.setNull(2, java.sql.Types.VARCHAR);
                ps.setShort(3, (short) mode);
                ps.setInt(4, ids.size());
                if (filterJson != null) ps.setString(5, filterJson);
                else ps.setNull(5, java.sql.Types.VARCHAR);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        batchId = keys.getLong(1);
                    } else {
                        throw new SQLException("Failed to obtain generated batch id");
                    }
                }
            }
            if (!ids.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO vg_rollback_batch_actions(batch_id, action_id) VALUES (?,?)")) {
                    for (Long aid : ids) {
                        ps.setLong(1, batchId);
                        ps.setLong(2, aid);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            c.commit();
            return batchId;
        } catch (SQLException ex) {
            try { c.rollback(); } catch (SQLException ignored) {}
            throw ex;
        } finally {
            try { c.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            release(c);
        }
    }

    @Override
    public int closeRollbackBatch(long batchId) throws SQLException {
        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE vg_rollback_batches SET completed = 1 WHERE id = ?")) {
            ps.setLong(1, batchId);
            return ps.executeUpdate();
        } finally {
            release(c);
        }
    }

    @Override
    public List<Long> findIncompleteBatchActionIds() throws SQLException {
        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ba.action_id FROM vg_rollback_batch_actions ba "
              + "JOIN vg_rollback_batches b ON b.id = ba.batch_id "
              + "WHERE b.completed = 0 ORDER BY ba.batch_id, ba.action_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        } finally {
            release(c);
        }
    }

    // ------------------------------------------------------------------ RESOLVE

    @Override
    public int resolveUser(UUID uuid, String name) throws SQLException {
        Connection c = borrow();
        try {
            return resolveUserOn(c, uuid, name);
        } finally {
            release(c);
        }
    }

    private int resolveUserOn(Connection c, UUID uuid, String name) throws SQLException {
        String resolveName = name != null ? name : "#unknown";
        if (uuid != null) {
            Integer cached = userIdByUuid.get(uuid);
            if (cached != null) return cached;
        } else {
            Integer cached = userIdByName.get(resolveName);
            if (cached != null) return cached;
        }
        // SELECT first
        Integer found = null;
        if (uuid != null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM vg_users WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) found = rs.getInt(1);
                }
            }
        }
        if (found == null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM vg_users WHERE name = ? AND uuid IS NULL")) {
                ps.setString(1, resolveName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) found = rs.getInt(1);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (found == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO vg_users(uuid, name, first_seen, last_seen) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                if (uuid != null) ps.setString(1, uuid.toString());
                else ps.setNull(1, java.sql.Types.VARCHAR);
                ps.setString(2, resolveName);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) found = keys.getInt(1);
                }
            }
            if (found == null) {
                // some drivers don't return keys via RETURN_GENERATED_KEYS for our PK type — fallback select.
                try (PreparedStatement ps = c.prepareStatement(
                        uuid != null ? "SELECT id FROM vg_users WHERE uuid = ?"
                                     : "SELECT id FROM vg_users WHERE name = ? AND uuid IS NULL")) {
                    ps.setString(1, uuid != null ? uuid.toString() : resolveName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) found = rs.getInt(1);
                    }
                }
            }
        } else {
            // v1.3.1 X6 (P3-8): amortize the last_seen UPDATE. On the very first
            // resolve per (uuid|name) after boot we still write; subsequent hits for
            // the same user id skip the round-trip until LAST_SEEN_DRIFT_MS has
            // elapsed. This trims out synchronous UPDATEs on the queue worker for
            // hot players (repeated inserts arrive at hundreds/sec at busy join times).
            Long lastWrote = lastSeenLastWriteMs.get(found);
            if (lastWrote == null || (now - lastWrote) > LAST_SEEN_DRIFT_MS) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE vg_users SET last_seen = ? WHERE id = ?")) {
                    ps.setLong(1, now);
                    ps.setInt(2, found);
                    ps.executeUpdate();
                }
                cacheIfWithinBound(lastSeenLastWriteMs, found, now, LAST_SEEN_CACHE_CAP);
            }
        }
        if (found == null) {
            throw new SQLException("Failed to resolve/insert user: uuid=" + uuid + " name=" + resolveName);
        }
        if (uuid != null) {
            cacheIfWithinBound(userIdByUuid, uuid, found, USER_ID_CACHE_CAP);
        } else {
            cacheIfWithinBound(userIdByName, resolveName, found, USER_ID_CACHE_CAP);
        }
        return found;
    }

    @Override
    public int resolveWorld(String key) throws SQLException {
        Connection c = borrow();
        try {
            return resolveWorldOn(c, key);
        } finally {
            release(c);
        }
    }

    private int resolveWorldOn(Connection c, String key) throws SQLException {
        if (key == null) key = "minecraft:overworld";
        Integer cached = worldIdByKey.get(key);
        if (cached != null) return cached;
        Integer found = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM vg_worlds WHERE world_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) found = rs.getInt(1);
            }
        }
        if (found == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO vg_worlds(world_key) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, key);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) found = keys.getInt(1);
                }
            }
            if (found == null) {
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM vg_worlds WHERE world_key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) found = rs.getInt(1);
                    }
                }
            }
        }
        if (found == null) {
            throw new SQLException("Failed to resolve/insert world: " + key);
        }
        cacheIfWithinBound(worldIdByKey, key, found, WORLD_ID_CACHE_CAP);
        return found;
    }

    // ------------------------------------------------------------------ RAW JDBC (backend-migration)

    /**
     * Grant a caller (backend migration job) raw access to a borrowed
     * {@link Connection}. The connection is released on return regardless of
     * whether {@code action} completed normally.
     */
    @Override
    public <T> T withRawConnection(RawJdbcAccess.SqlAction<T> action) throws SQLException {
        Connection c = borrow();
        try {
            return action.run(c);
        } finally {
            release(c);
        }
    }

    /** Dialect surfaced to the backend-migration package. */
    public Schema.Dialect currentDialect() {
        return dialect();
    }

    // ------------------------------------------------------------------ API PROBE (W3-B12)

    @Override
    public boolean hasActionsInWindow(UUID user, String worldId, int x, int y, int z,
                                      ActionType[] types, long withinMillis) throws SQLException {
        if (user == null) throw new IllegalArgumentException("user must not be null");
        if (worldId == null) throw new IllegalArgumentException("worldId must not be null");

        // Build the WHERE incrementally so we can skip clauses when unbounded / any-type.
        StringBuilder sb = new StringBuilder(
                "SELECT 1 FROM vg_actions a "
              + "JOIN vg_users  u ON u.id = a.user_id "
              + "JOIN vg_worlds w ON w.id = a.world_id "
              + "WHERE u.uuid = ? AND w.world_key = ? "
              + "AND a.x = ? AND a.y = ? AND a.z = ?");
        List<Object> binds = new ArrayList<>();
        binds.add(user.toString());
        binds.add(worldId);
        binds.add(x);
        binds.add(y);
        binds.add(z);

        if (withinMillis > 0L) {
            sb.append(" AND a.ts >= ?");
            binds.add(System.currentTimeMillis() - withinMillis);
        }
        if (types != null && types.length > 0) {
            sb.append(" AND a.type IN (");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('?');
                binds.add(types[i].id());
            }
            sb.append(')');
        }
        sb.append(" LIMIT 1");

        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
            bind(ps, binds);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } finally {
            release(c);
        }
    }

    // ------------------------------------------------------------------ UTIL

    static void bind(PreparedStatement ps, List<Object> binds) throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            Object v = binds.get(i);
            if (v == null) ps.setObject(i + 1, null);
            else if (v instanceof Integer iv) ps.setInt(i + 1, iv);
            else if (v instanceof Long lv) ps.setLong(i + 1, lv);
            else if (v instanceof Short sv) ps.setShort(i + 1, sv);
            else if (v instanceof Boolean bv) ps.setInt(i + 1, bv ? 1 : 0);
            else ps.setString(i + 1, v.toString());
        }
    }
}
