package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.Schema;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-B10 — assert dialect-specific SQL is emitted by {@link AbstractJdbcDao#optimize(long)}
 * for MySQL, PostgreSQL, and SQLite paths. Uses a Mockito {@link Connection}
 * so we don't need a live DB — the point of this test is the SQL text.
 */
class AbstractJdbcDaoOptimizeSqlTest {

    /** Test double: fixed dialect, mock connection returned to {@code borrow()}. */
    private static final class HarnessDao extends AbstractJdbcDao {
        final Connection conn;
        final Schema.Dialect d;
        HarnessDao(Schema.Dialect d, Connection conn) {
            this.conn = conn;
            this.d = d;
        }
        @Override protected Connection borrow() { return conn; }
        @Override protected void release(Connection c) { /* no-op */ }
        @Override protected Schema.Dialect dialect() { return d; }
        @Override public boolean isHealthy() { return true; }
        @Override public void close() { }
        // Skip size probe so we don't need to mock the SELECT.
        @Override protected long safeSizeBytes() { return -1L; }
    }

    private static Connection mockConn() throws SQLException {
        Connection c = mock(Connection.class);
        when(c.getAutoCommit()).thenReturn(true);
        Statement st = mock(Statement.class);
        when(c.createStatement()).thenReturn(st);
        when(st.execute(anyString())).thenReturn(false);
        // executeQuery is unused because safeSizeBytes is stubbed out.
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(anyString())).thenReturn(rs);
        return c;
    }

    private static List<String> captureExecuted(Connection c) throws SQLException {
        Statement st = c.createStatement();
        // Re-stub so we can capture. captureExecuted is called AFTER the run
        // where the same mock statement recorded invocations.
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(st, atLeast(0)).execute(cap.capture());
        return new ArrayList<>(cap.getAllValues());
    }

    @Test
    void mysql_emits_optimize_table_on_all_three_tables() throws Exception {
        Connection c = mockConn();
        HarnessDao dao = new HarnessDao(Schema.Dialect.MYSQL, c);

        GuardianDao.OptimizeResult r = dao.optimize(60_000L);

        List<String> sqls = captureExecuted(c);
        assertThat(sqls).hasSize(1);
        String sql = sqls.get(0);
        assertThat(sql).startsWith("OPTIMIZE TABLE ");
        assertThat(sql).contains("vg_actions");
        assertThat(sql).contains("vg_rollback_batches");
        assertThat(sql).contains("vg_rollback_batch_actions");
        assertThat(r.completed()).isTrue();
    }

    @Test
    void postgres_emits_vacuum_analyze_per_table_in_autocommit() throws Exception {
        Connection c = mockConn();
        // Simulate a JDBC connection that started in a txn — optimize() must flip it.
        when(c.getAutoCommit()).thenReturn(false);
        HarnessDao dao = new HarnessDao(Schema.Dialect.POSTGRES, c);

        GuardianDao.OptimizeResult r = dao.optimize(60_000L);

        List<String> sqls = captureExecuted(c);
        assertThat(sqls).containsExactly(
            "VACUUM ANALYZE vg_actions",
            "VACUUM ANALYZE vg_rollback_batches",
            "VACUUM ANALYZE vg_rollback_batch_actions");
        // Must have flipped to autocommit before executing VACUUM.
        verify(c, atLeastOnce()).setAutoCommit(true);
        assertThat(r.completed()).isTrue();
    }

    @Test
    void sqlite_emits_whole_db_vacuum() throws Exception {
        Connection c = mockConn();
        HarnessDao dao = new HarnessDao(Schema.Dialect.SQLITE, c);

        GuardianDao.OptimizeResult r = dao.optimize(60_000L);

        List<String> sqls = captureExecuted(c);
        assertThat(sqls).containsExactly("VACUUM");
        assertThat(r.completed()).isTrue();
    }

    @Test
    void sql_error_is_swallowed_and_marked_incomplete() throws Exception {
        Connection c = mock(Connection.class);
        when(c.getAutoCommit()).thenReturn(true);
        Statement st = mock(Statement.class);
        when(c.createStatement()).thenReturn(st);
        when(st.execute(anyString())).thenThrow(new SQLException("boom"));

        HarnessDao dao = new HarnessDao(Schema.Dialect.MYSQL, c);
        GuardianDao.OptimizeResult r = dao.optimize(60_000L);

        // Purge must not see the exception — result records incomplete=true.
        assertThat(r.completed()).isFalse();
        assertThat(r.durationMillis()).isGreaterThanOrEqualTo(0L);
    }
}
