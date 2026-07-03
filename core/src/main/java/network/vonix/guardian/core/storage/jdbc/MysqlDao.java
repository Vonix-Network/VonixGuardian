package network.vonix.guardian.core.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;

/**
 * MySQL-backed DAO. Connection pool managed by HikariCP. <b>Beta in v0.1.0</b>.
 *
 * <p>v1.3.1 X6: server-side prepared-statement caching enabled by default via
 * {@code cachePrepStmts / prepStmtCacheSize / prepStmtCacheSqlLimit /
 * useServerPrepStmts}. Under a busy audit stream the queue worker re-prepares
 * the same {@code INSERT INTO vg_actions ...} / {@code SELECT ... FROM
 * vg_users ...} statements thousands of times per second; the server-side
 * cache eliminates the per-execute parse cost that dominated the DAO round-trip
 * on high-throughput MySQL backends. Values match Hikari's own MySQL
 * <a href="https://github.com/brettwooldridge/HikariCP/wiki/MySQL-Configuration">recommendation</a>.
 */
public final class MysqlDao extends AbstractJdbcDao {

    private final HikariDataSource ds;

    public MysqlDao(GuardianConfig.Database cfg) {
        this(cfg, null, 0);
    }

    public MysqlDao(GuardianConfig.Database cfg, Semaphore lookupSemaphore, int maxResultRows) {
        super(lookupSemaphore, maxResultRows);
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl());
        if (cfg.user() != null) hc.setUsername(cfg.user());
        if (cfg.password() != null) hc.setPassword(cfg.password());
        GuardianConfig.Hikari hk = cfg.hikari() != null ? cfg.hikari() : GuardianConfig.Hikari.defaults();
        hc.setMaximumPoolSize(hk.maxPoolSize());
        hc.setConnectionTimeout(hk.connectionTimeoutMs());
        if (hk.maxLifetimeMs() > 0L) {
            hc.setMaxLifetime(hk.maxLifetimeMs());
        }
        if (hk.leakDetectionMs() > 0L) {
            hc.setLeakDetectionThreshold(hk.leakDetectionMs());
        }
        hc.setPoolName("VonixGuardian-MySQL");
        hc.setAutoCommit(true);
        // v1.3.1 X6 — server-side prepared-statement cache. Every batch insert +
        // resolveUserOn SELECT + hasActionsInWindow SELECT + QueryCompiler prepare
        // re-parses on the MySQL server without these knobs. Values track HikariCP's
        // MySQL recommendation and are cheap (<1 MB per connection).
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hc.addDataSourceProperty("useServerPrepStmts", "true");
        this.ds = new HikariDataSource(hc);
    }

    @Override
    protected Connection borrow() throws SQLException {
        return ds.getConnection();
    }

    @Override
    protected void release(Connection c) {
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    @Override
    protected Schema.Dialect dialect() {
        return Schema.Dialect.MYSQL;
    }

    @Override
    public boolean isHealthy() {
        if (ds.isClosed()) return false;
        try (Connection c = ds.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException ex) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!ds.isClosed()) {
            ds.close();
        }
    }
}
