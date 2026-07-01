package network.vonix.guardian.core.storage.dbmigrate;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Marker interface implemented by JDBC-backed DAOs to grant the backend
 * migrator raw {@link Connection} access. This is intentionally NOT part of
 * {@code GuardianDao} — normal write / query paths must go through the DAO
 * contract; only the {@link BackendMigrationJob} needs low-level access to
 * copy rows verbatim between backends.
 *
 * @since 1.2.0
 */
public interface RawJdbcAccess {

    /** A callback that runs SQL directly against a borrowed {@link Connection}. */
    @FunctionalInterface
    interface SqlAction<T> {
        T run(Connection c) throws SQLException;
    }

    /**
     * Borrow a connection, run {@code action}, and release the connection on
     * return (whether success or exception).
     *
     * @param action work to perform against the raw JDBC connection
     * @return whatever {@code action} returns
     * @throws SQLException on borrow, action, or release failure
     */
    <T> T withRawConnection(SqlAction<T> action) throws SQLException;
}
