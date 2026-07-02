package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.Schema;
import network.vonix.guardian.core.storage.jdbc.AbstractJdbcDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5-05 — end-to-end wiring test asserting {@link PurgeEngine} triggers
 * dialect-appropriate storage optimization when {@code #optimize} is set on
 * the {@link QueryFilter}.
 *
 * <p>Gated behavior verified here:
 * <ul>
 *   <li>MySQL + {@code #optimize} → {@code OPTIMIZE TABLE ...} is executed.</li>
 *   <li>MySQL without {@code #optimize} → no OPTIMIZE SQL runs.</li>
 *   <li>PostgreSQL + {@code #optimize} → {@code VACUUM ANALYZE} runs (documented
 *       fallback; not a MySQL-only feature).</li>
 *   <li>SQLite + {@code #optimize} → {@code VACUUM} runs (documented no-op parity;
 *       exercises the SQLite fallback path).</li>
 * </ul>
 *
 * <p>The dao here is a subclass of {@link AbstractJdbcDao} with a mocked
 * {@link Connection} so we can capture the exact SQL text without needing a
 * live database. The {@code purge(QueryFilter)} step is stubbed so the test
 * exercises only the gate that fans out into
 * {@link AbstractJdbcDao#optimize(long)}.
 */
class OptimizeFlagTest {

    /** Fake DAO — fixed dialect, mock connection, purge() short-circuited. */
    private static final class HarnessDao extends AbstractJdbcDao {
        final Connection conn;
        final Schema.Dialect d;
        long deleted;
        HarnessDao(Schema.Dialect d, Connection conn, long deleted) {
            this.conn = conn;
            this.d = d;
            this.deleted = deleted;
        }
        @Override protected Connection borrow() { return conn; }
        @Override protected void release(Connection c) { /* no-op */ }
        @Override protected Schema.Dialect dialect() { return d; }
        @Override public boolean isHealthy() { return true; }
        @Override public void close() { }
        // Skip size probe so we don't need to mock any SELECTs.
        @Override protected long safeSizeBytes() { return -1L; }
        QueryFilter lastPurgeFilter;
        // Bypass real DELETE SQL — this test only cares about the OPTIMIZE gate.
        @Override public long purge(QueryFilter filter) {
            this.lastPurgeFilter = filter;
            return deleted;
        }
    }

    private static Connection mockConn() throws SQLException {
        Connection c = mock(Connection.class);
        when(c.getAutoCommit()).thenReturn(true);
        Statement st = mock(Statement.class);
        when(c.createStatement()).thenReturn(st);
        when(st.execute(anyString())).thenReturn(false);
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(anyString())).thenReturn(rs);
        return c;
    }

    private static List<String> captureExecuted(Connection c) throws SQLException {
        Statement st = c.createStatement();
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(st, atLeast(0)).execute(cap.capture());
        return new ArrayList<>(cap.getAllValues());
    }

    /** Time filter old enough to satisfy any minAgeSeconds we pass here. */
    private static QueryFilter withOptimize(boolean optimize) {
        long veryOld = System.currentTimeMillis() - (365L * 86_400_000L); // 1yr ago
        return QueryFilter.builder()
                .sinceMillis(veryOld)
                .optimize(optimize)
                .build();
    }

    @Test
    void mysql_optimize_flag_triggers_OPTIMIZE_TABLE() throws Exception {
        Connection c = mockConn();
        HarnessDao dao = new HarnessDao(Schema.Dialect.MYSQL, c, /*deleted=*/42L);
        PurgeEngine engine = new PurgeEngine(dao);

        PurgeEngine.PurgeResult r = engine.purge(withOptimize(true), 86_400L);

        assertThat(r.deletedCount()).isEqualTo(42L);
        assertThat(r.optimized()).isTrue();
        assertThat(dao.lastPurgeFilter.sinceMillis()).isNull();
        assertThat(dao.lastPurgeFilter.untilMillis()).isEqualTo(r.requestedSinceMs());

        List<String> sqls = captureExecuted(c);
        assertThat(sqls).hasSize(1);
        String sql = sqls.get(0);
        assertThat(sql).startsWith("OPTIMIZE TABLE ");
        assertThat(sql).contains("vg_actions");
    }

    @Test
    void mysql_without_optimize_flag_does_NOT_run_OPTIMIZE_TABLE() throws Exception {
        Connection c = mockConn();
        HarnessDao dao = new HarnessDao(Schema.Dialect.MYSQL, c, /*deleted=*/7L);
        PurgeEngine engine = new PurgeEngine(dao);

        PurgeEngine.PurgeResult r = engine.purge(withOptimize(false), 86_400L);

        assertThat(r.deletedCount()).isEqualTo(7L);
        assertThat(r.optimized()).isFalse();

        // No Statement should have been created at all — optimize() was never called.
        verify(c, never()).createStatement();
    }

    @Test
    void postgres_optimize_flag_runs_VACUUM_ANALYZE_fallback() throws Exception {
        Connection c = mockConn();
        HarnessDao dao = new HarnessDao(Schema.Dialect.POSTGRES, c, /*deleted=*/3L);
        PurgeEngine engine = new PurgeEngine(dao);

        PurgeEngine.PurgeResult r = engine.purge(withOptimize(true), 86_400L);

        assertThat(r.optimized()).isTrue();
        List<String> sqls = captureExecuted(c);
        assertThat(sqls).isNotEmpty();
        assertThat(sqls.get(0)).startsWith("VACUUM ANALYZE ");
    }

    @Test
    void sqlite_optimize_flag_runs_VACUUM_fallback() throws Exception {
        Connection c = mockConn();
        HarnessDao dao = new HarnessDao(Schema.Dialect.SQLITE, c, /*deleted=*/1L);
        PurgeEngine engine = new PurgeEngine(dao);

        PurgeEngine.PurgeResult r = engine.purge(withOptimize(true), 86_400L);

        assertThat(r.optimized()).isTrue();
        List<String> sqls = captureExecuted(c);
        assertThat(sqls).containsExactly("VACUUM");
    }
}
