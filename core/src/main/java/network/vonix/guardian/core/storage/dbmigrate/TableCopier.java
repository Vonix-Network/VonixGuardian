package network.vonix.guardian.core.storage.dbmigrate;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Copy every row of one table from a source JDBC {@link Connection} to a
 * destination {@link Connection}, in {@code LIMIT/OFFSET} chunks. Preserves the
 * destination's primary-key column identity — the {@code id} column is copied
 * verbatim so cross-table references (e.g. {@code vg_actions.user_id ->
 * vg_users.id}) stay valid after migration.
 *
 * <p>The copier is stateless and safe to reuse across tables.
 *
 * <h2>Resumability</h2>
 * Callers can start at any {@code startOffset}; the destination is not
 * truncated by the copier itself. If the destination already contains rows
 * with the ids being inserted, the driver will raise a PK-conflict which
 * bubbles up as {@link SQLException} — the {@link BackendMigrationJob}
 * refuses to run against a non-empty destination unless {@code --force} is
 * passed, which is checked one layer up.
 *
 * <h2>Chunking</h2>
 * We stream {@code chunkSize} rows per SELECT to bound memory. Between chunks
 * we {@code commit()} the destination so a mid-migration crash leaves a
 * well-defined resume point (the number of rows currently in the destination
 * table).
 */
final class TableCopier {

    private final int chunkSize;

    TableCopier(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        this.chunkSize = chunkSize;
    }

    /**
     * Copy every row of {@code table} from {@code source} to {@code dest}.
     *
     * @param source        source connection
     * @param dest          destination connection ({@code autoCommit} will be
     *                      toggled off / on internally)
     * @param destDialect   dialect of destination (used for identity-restart on
     *                      PostgreSQL / MySQL)
     * @param table         SQL table name
     * @param orderBy       column to ORDER BY for a stable chunk boundary
     *                      (typically the primary key)
     * @param onProgress    called after every chunk (and at least every 5s if
     *                      the chunk itself takes longer) with cumulative rows
     *                      copied
     * @return total rows copied
     * @throws SQLException on any JDBC error; the destination is rolled back
     *                      to the last chunk boundary
     */
    long copy(Connection source,
              Connection dest,
              Schema.Dialect destDialect,
              String table,
              String orderBy,
              LongConsumer onProgress) throws SQLException {

        // Count first so ProgressUpdate can carry a meaningful denominator.
        long total;
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            total = rs.next() ? rs.getLong(1) : 0L;
        }
        if (total == 0L) {
            onProgress.accept(0L);
            return 0L;
        }

        // Peek at the first row's metadata to build the INSERT statement.
        List<String> cols;
        try (Statement st = source.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 1")) {
            ResultSetMetaData md = rs.getMetaData();
            cols = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(md.getColumnLabel(i));
            }
        }
        String colList = String.join(", ", cols);
        StringBuilder qMarks = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) qMarks.append(',');
            qMarks.append('?');
        }
        String insertSql = "INSERT INTO " + table + " (" + colList + ") VALUES (" + qMarks + ")";

        boolean prevAutoCommit = dest.getAutoCommit();
        long copied = 0L;
        try {
            dest.setAutoCommit(false);
            long offset = 0L;
            while (offset < total) {
                String selectSql = "SELECT " + colList + " FROM " + table
                    + " ORDER BY " + orderBy + " LIMIT " + chunkSize + " OFFSET " + offset;
                try (PreparedStatement selPs = source.prepareStatement(selectSql);
                     ResultSet rs = selPs.executeQuery();
                     PreparedStatement insPs = dest.prepareStatement(insertSql)) {
                    int batched = 0;
                    while (rs.next()) {
                        for (int i = 1; i <= cols.size(); i++) {
                            Object val = rs.getObject(i);
                            if (val == null) {
                                insPs.setNull(i, Types.NULL);
                            } else {
                                insPs.setObject(i, val);
                            }
                        }
                        insPs.addBatch();
                        batched++;
                    }
                    if (batched > 0) {
                        insPs.executeBatch();
                    }
                    copied += batched;
                    if (batched == 0) {
                        // Source shrank underneath us — safe to stop.
                        break;
                    }
                }
                dest.commit();
                onProgress.accept(copied);
                offset += chunkSize;
            }
            // Repair identity/sequence on dialects that don't auto-detect after
            // manual id inserts, so subsequent INSERTs on the destination
            // continue from max(id)+1.
            resetIdentity(dest, destDialect, table);
        } catch (SQLException ex) {
            try { dest.rollback(); } catch (SQLException ignored) {}
            throw ex;
        } finally {
            try { dest.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
        }
        return copied;
    }

    /**
     * After copying rows with explicit ids, some dialects need their
     * auto-increment sequences bumped past {@code max(id)}. This is a no-op
     * on SQLite (its rowid auto-advances). On MySQL we {@code ALTER TABLE ...
     * AUTO_INCREMENT}; on PostgreSQL we call {@code setval()} on the sequence
     * backing the {@code id BIGSERIAL} column.
     */
    private void resetIdentity(Connection dest, Schema.Dialect dialect, String table) throws SQLException {
        // Tables without an integer 'id' column (composite PKs, e.g.
        // vg_rollback_batch_actions and vg_schema_version) need no sequence bump.
        if (!hasIdColumn(dest, table)) {
            return;
        }
        long max;
        try (Statement st = dest.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id),0) FROM " + table)) {
            max = rs.next() ? rs.getLong(1) : 0L;
        }
        switch (dialect) {
            case SQLITE -> {
                // No-op: SQLite's AUTOINCREMENT tracker is updated by inserts.
            }
            case MYSQL -> {
                try (Statement st = dest.createStatement()) {
                    st.execute("ALTER TABLE " + table + " AUTO_INCREMENT = " + (max + 1));
                }
                dest.commit();
            }
            case POSTGRES -> {
                try (Statement st = dest.createStatement()) {
                    // pg_get_serial_sequence returns the sequence backing id BIGSERIAL.
                    st.execute("SELECT setval(pg_get_serial_sequence('" + table + "','id'), "
                        + (max + 1) + ", false)");
                }
                dest.commit();
            }
        }
    }

    private static boolean hasIdColumn(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                if ("id".equalsIgnoreCase(md.getColumnLabel(i))) {
                    return true;
                }
            }
            return false;
        }
    }
}
