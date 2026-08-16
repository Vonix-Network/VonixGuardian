package network.vonix.guardian.core.storage.jdbc;

import network.vonix.guardian.core.storage.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractJdbcDaoSafetyTest {

    private static final class HarnessDao extends AbstractJdbcDao {
        private final Connection connection;
        private boolean released;
        private Throwable releaseFailure;

        private HarnessDao(Connection connection) {
            super(null, 0);
            this.connection = connection;
        }

        @Override
        protected Connection borrow() {
            return connection;
        }

        @Override
        protected void release(Connection connection) {
            released = true;
        }

        private void failRelease(Throwable failure) {
            releaseFailure = failure;
        }

        @Override
        protected void releaseChecked(Connection connection) throws SQLException {
            released = true;
            if (releaseFailure != null) {
                if (releaseFailure instanceof SQLException ex) throw ex;
                if (releaseFailure instanceof RuntimeException ex) throw ex;
                if (releaseFailure instanceof Error ex) throw ex;
                throw new SQLException("release failed", releaseFailure);
            }
            super.releaseChecked(connection);
        }

        @Override
        protected Schema.Dialect dialect() {
            return Schema.Dialect.SQLITE;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    @Test
    void getAutoCommitFailureStillReleasesBorrowedConnection() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenThrow(new SQLException("auto-commit probe failed"));
        HarnessDao dao = new HarnessDao(connection);

        assertThatThrownBy(() -> dao.markRolledBack(List.of(7L), true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("auto-commit probe failed");
        assertThat(dao.released).isTrue();
    }

    @Test
    void autoCommitRestorationFailureInvalidatesAndReleasesConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        doThrow(new SQLException("auto-commit restore failed"))
                .when(connection).setAutoCommit(true);
        HarnessDao dao = new HarnessDao(connection);

        assertThatThrownBy(() -> dao.markRolledBack(List.of(8L), true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("auto-commit restore failed");
        verify(connection).close();
        assertThat(dao.released).isTrue();
    }

    @Test
    void runtimeCommitFailureRollsBackAndReleasesConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        doThrow(new IllegalStateException("commit failed")).when(connection).commit();
        HarnessDao dao = new HarnessDao(connection);

        assertThatThrownBy(() -> dao.markRolledBack(List.of(9L), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit failed");
        verify(connection).rollback();
        assertThat(dao.released).isTrue();
    }

    @Test
    void runtimeRestorationFailureStillInvalidatesAndReleasesConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        doThrow(new IllegalStateException("runtime restore failed"))
                .when(connection).setAutoCommit(true);
        HarnessDao dao = new HarnessDao(connection);

        assertThatThrownBy(() -> dao.markRolledBack(List.of(10L), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("runtime restore failed");
        verify(connection).close();
        assertThat(dao.released).isTrue();
    }

    @Test
    void checkedReleaseFailureIsSurfacedAndPrimaryFailureIsPreserved() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        doThrow(new IllegalStateException("commit failed")).when(connection).commit();
        HarnessDao dao = new HarnessDao(connection);
        dao.failRelease(new SQLException("pool release failed"));

        assertThatThrownBy(() -> dao.markRolledBack(List.of(11L), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit failed")
                .satisfies(error -> assertThat(error.getSuppressed())
                        .anyMatch(suppressed -> suppressed.getMessage().contains("pool release failed")));
        assertThat(dao.released).isTrue();
    }

    @Test
    void uncheckedReleaseFailureIsSurfacedAndReleaseWasAttempted() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        HarnessDao dao = new HarnessDao(connection);
        dao.failRelease(new IllegalStateException("pool release runtime failure"));

        assertThatThrownBy(() -> dao.markRolledBack(List.of(12L), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pool release runtime failure");
        assertThat(dao.released).isTrue();
    }
}
