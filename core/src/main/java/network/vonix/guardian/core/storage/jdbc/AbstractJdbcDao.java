package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.QueryCompiler;
import network.vonix.guardian.core.storage.Schema;

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

/**
 * Shared JDBC implementation. Subclasses supply a {@link Connection} per call and the
 * {@link Schema.Dialect} so the right DDL is generated.
 *
 * <p>Concurrency: {@code getConnection()} returns either a pooled connection (Hikari) or a
 * single serialised connection (SQLite). The DAO contract states callers are worker
 * threads, never the server thread.
 *
 * <p>Read-side rate limit: {@link #query} and {@link #count} acquire a permit from
 * {@code lookupSemaphore} (sized at construction by {@code config.lookup().maxConcurrent()})
 * for the duration of the call, providing simple back-pressure for ad-hoc operator
 * queries. If the semaphore is {@code null}, no rate limit is applied (test-only path).
 *
 * <p>Result-size cap: {@link #query} clamps the {@code limit} argument to
 * {@code min(limit, maxResultRows)}. A value of {@code 0} disables the cap.
 */
public abstract class AbstractJdbcDao implements GuardianDao {

    private static final String INSERT_ACTION_SQL =
        "INSERT INTO vg_actions("
        + "ts, type, user_id, world_id, x, y, z, target, meta, amount, rolled_back, source_tag"
        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

    /** uuid-string -> id, only populated for real (non-null UUID) users. */
    private final ConcurrentHashMap<String, Integer> userIdByUuid = new ConcurrentHashMap<>();
    /** name -> id, used for sentinels and uuid-less lookups. */
    private final ConcurrentHashMap<String, Integer> userIdByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> worldIdByKey = new ConcurrentHashMap<>();

    /** Optional read-side rate limit. May be {@code null} (no limit). */
    private final Semaphore lookupSemaphore;

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

    /** Release a connection (no-op for pooled, no-op for the single-connection case). */
    protected abstract void release(Connection c);

    /** Dialect identifier. */
    protected abstract Schema.Dialect dialect();

    /** Health check — implementations probe the underlying pool/connection. */
    @Override
    public abstract boolean isHealthy();

    @Override
    public void init() throws SQLException {
        Connection c = borrow();
        try {
            Schema.createTables(c, dialect());
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
                    ps.addBatch();
                }
                int[] r = ps.executeBatch();
                c.commit();
                int total = 0;
                for (int v : r) {
                    if (v >= 0) total += v;
                    else total += 1; // SUCCESS_NO_INFO
                }
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

    private static String nullSafeName(Action a) {
        return a.actorName() != null ? a.actorName() : "#unknown";
    }

    // ------------------------------------------------------------------ SELECT

    @Override
    public List<Action> query(QueryFilter filter, int offset, int limit) throws SQLException {
        int effectiveLimit = (maxResultRows > 0) ? Math.min(limit, maxResultRows) : limit;
        acquireLookupPermit();
        try {
            QueryCompiler.Compiled q = QueryCompiler.compileSelect(filter, offset, effectiveLimit);
            Connection c = borrow();
            try (PreparedStatement ps = c.prepareStatement(q.sql())) {
                bind(ps, q.binds());
                try (ResultSet rs = ps.executeQuery()) {
                    List<Action> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(readAction(rs));
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
            lookupSemaphore.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for lookup permit", ex);
        }
    }

    private void releaseLookupPermit() {
        if (lookupSemaphore != null) {
            lookupSemaphore.release();
        }
    }

    private Action readAction(ResultSet rs) throws SQLException {
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
        return new Action(id, ts, type, uuid, name, worldKey, x, y, z, target, meta, amount, rolledBack, sourceTag);
    }

    private static UUID safeUuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    // ------------------------------------------------------------------ MUTATE

    @Override
    public int markRolledBack(List<Long> ids, boolean rolledBack) throws SQLException {
        if (ids == null || ids.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder("UPDATE vg_actions SET rolled_back = ? WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        sb.append(')');
        Connection c = borrow();
        try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
            ps.setInt(1, rolledBack ? 1 : 0);
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 2, ids.get(i));
            }
            return ps.executeUpdate();
        } finally {
            release(c);
        }
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
            Integer cached = userIdByUuid.get(uuid.toString());
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
            try (PreparedStatement ps = c.prepareStatement("UPDATE vg_users SET last_seen = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setInt(2, found);
                ps.executeUpdate();
            }
        }
        if (found == null) {
            throw new SQLException("Failed to resolve/insert user: uuid=" + uuid + " name=" + resolveName);
        }
        if (uuid != null) userIdByUuid.put(uuid.toString(), found);
        else userIdByName.put(resolveName, found);
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
        worldIdByKey.put(key, found);
        return found;
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
