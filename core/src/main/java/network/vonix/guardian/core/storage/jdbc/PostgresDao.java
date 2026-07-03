package network.vonix.guardian.core.storage.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;

/**
 * PostgreSQL-backed DAO. Connection pool managed by HikariCP. <b>Beta in v0.1.0</b>.
 *
 * <p>v1.3.1 X6: server-side prepared-statement threshold reduced to {@code 3}
 * via {@code prepareThreshold} — after three executes of the same PreparedStatement
 * the pgJDBC driver switches to a named server-side prepared statement, matching
 * the MySQL prep-stmt-cache tuning done in {@link MysqlDao} for high-throughput
 * audit backends.
 */
public final class PostgresDao extends AbstractJdbcDao {

    private final HikariDataSource ds;

    public PostgresDao(GuardianConfig.Database cfg) {
        this(cfg, null, 0);
    }

    public PostgresDao(GuardianConfig.Database cfg, Semaphore lookupSemaphore, int maxResultRows) {
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
        hc.setPoolName("VonixGuardian-Postgres");
        hc.setAutoCommit(true);
        // v1.3.1 X6 — pgJDBC server-side prepared-statement threshold. Default is 5,
        // dropping to 3 amortizes the parse cost across the whole batched flush loop.
        hc.addDataSourceProperty("prepareThreshold", "3");
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
        return Schema.Dialect.POSTGRES;
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
