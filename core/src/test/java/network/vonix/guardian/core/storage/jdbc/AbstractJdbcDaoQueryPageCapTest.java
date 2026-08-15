package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractJdbcDaoQueryPageCapTest {

    private static final class CapDao extends AbstractJdbcDao {
        private final Connection conn;

        CapDao(Connection conn, int maxResultRows) {
            super(null, maxResultRows);
            this.conn = conn;
        }

        @Override protected Connection borrow() { return conn; }
        @Override protected void release(Connection c) { }
        @Override protected Schema.Dialect dialect() { return Schema.Dialect.SQLITE; }
        @Override public boolean isHealthy() { return true; }
        @Override public void close() { }
        @Override public void init() { }
    }

    private static Connection mockConnReturning(int rowCount) throws SQLException {
        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        AtomicInteger remaining = new AtomicInteger(rowCount);
        when(rs.next()).thenAnswer(inv -> remaining.getAndDecrement() > 0);
        // Minimal column values for readAction.
        when(rs.getLong(1)).thenReturn(1L);
        when(rs.getLong(2)).thenReturn(100L);
        when(rs.getInt(3)).thenReturn(ActionType.BLOCK_PLACE.id());
        when(rs.getString(4)).thenReturn(UUID.randomUUID().toString());
        when(rs.getString(5)).thenReturn("u");
        when(rs.getString(6)).thenReturn("minecraft:overworld");
        when(rs.getInt(7)).thenReturn(0);
        when(rs.getInt(8)).thenReturn(64);
        when(rs.getInt(9)).thenReturn(0);
        when(rs.getString(10)).thenReturn("minecraft:stone");
        when(rs.getString(11)).thenReturn(null);
        when(rs.getInt(12)).thenReturn(1);
        when(rs.getInt(13)).thenReturn(0);
        when(rs.getString(14)).thenReturn(null);
        when(rs.getString(15)).thenReturn(null);
        when(rs.getString(16)).thenReturn(null);
        when(rs.getBoolean(17)).thenReturn(false);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getString(18)).thenReturn(null);
        when(rs.getString(19)).thenReturn(null);
        when(rs.getBytes(20)).thenReturn(null);
        when(rs.getBytes(21)).thenReturn(null);
        when(rs.getBytes(22)).thenReturn(null);
        return c;
    }

    @Test
    void queryPageMarksTruncatedOnlyWhenCapIsFilled() throws Exception {
        Connection full = mockConnReturning(2);
        CapDao dao = new CapDao(full, 2);
        GuardianDao.QueryPage truncated = dao.queryPage(QueryFilter.empty(), 0, 5);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.rows()).hasSize(2);

        Connection shortPage = mockConnReturning(1);
        CapDao dao2 = new CapDao(shortPage, 2);
        GuardianDao.QueryPage eof = dao2.queryPage(QueryFilter.empty(), 0, 5);
        assertThat(eof.truncated()).isFalse();
        assertThat(eof.rows()).hasSize(1);
    }

    @Test
    void queryPageForDisplayMarksTruncatedOnlyWhenCapIsFilled() throws Exception {
        Connection full = mockConnReturning(2);
        CapDao dao = new CapDao(full, 2);
        GuardianDao.QueryPage truncated = dao.queryPageForDisplay(QueryFilter.empty(), 0, 5);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.rows()).hasSize(2);
        assertThat(truncated.rows().get(0).blockEntityNbt()).isNull();
    }
}
