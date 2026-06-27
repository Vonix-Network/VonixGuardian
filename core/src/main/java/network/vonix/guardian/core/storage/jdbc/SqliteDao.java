package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite-backed DAO. Uses a single {@link Connection} serialised through a
 * {@link ReentrantLock} — SQLite's writer is process-wide single-threaded, so this is
 * simpler and faster than juggling a pool.
 */
public final class SqliteDao extends AbstractJdbcDao {

    private final String jdbcUrl;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Connection connection;

    public SqliteDao(GuardianConfig.Database cfg) {
        String url = cfg != null ? cfg.jdbcUrl() : null;
        if (url == null || url.isBlank()) {
            String file = (cfg != null && cfg.file() != null && !cfg.file().isBlank())
                ? cfg.file()
                : "guardian.db";
            url = "jdbc:sqlite:" + file;
        }
        this.jdbcUrl = url;
    }

    /** Test-only constructor for explicit URLs (e.g. {@code jdbc:sqlite::memory:}). */
    public SqliteDao(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    protected Connection borrow() throws SQLException {
        lock.lock();
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(jdbcUrl);
                // Pragmas for sanity: WAL won't work on :memory: but is a no-op there.
                try (var st = connection.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                }
            }
            return connection;
        } catch (SQLException ex) {
            lock.unlock();
            throw ex;
        }
    }

    @Override
    protected void release(Connection c) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    protected Schema.Dialect dialect() {
        return Schema.Dialect.SQLITE;
    }

    @Override
    public boolean isHealthy() {
        try {
            Connection c = borrow();
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT 1")) {
                return rs.next();
            } finally {
                release(c);
            }
        } catch (SQLException ex) {
            return false;
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
                connection = null;
            }
        } finally {
            lock.unlock();
        }
    }
}
