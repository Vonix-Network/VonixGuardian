package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration v8 → v9: composite {@code (ts, id)} index covering the
 * lookup/rollback {@code ORDER BY ts DESC, id DESC} keyset.
 */
public final class V9TsIdIndex implements Migration {

    private static final int MYSQL_ERR_DUP_KEYNAME = 1061;

    @Override public int fromVersion() { return 8; }
    @Override public int toVersion() { return 9; }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        String ddl = dialect == Schema.Dialect.MYSQL
                ? "CREATE INDEX vg_actions_ts_id ON vg_actions(ts, id)"
                : "CREATE INDEX IF NOT EXISTS vg_actions_ts_id ON vg_actions(ts, id)";
        try (Statement st = c.createStatement()) {
            try {
                st.execute(ddl);
            } catch (SQLException ex) {
                if (!isDuplicateIndex(dialect, ex) && !isMissingTsColumn(dialect, ex)) {
                    throw ex;
                }
            }
        }
    }

    private static boolean isDuplicateIndex(Schema.Dialect dialect, SQLException ex) {
        if (dialect == Schema.Dialect.MYSQL && ex.getErrorCode() == MYSQL_ERR_DUP_KEYNAME) {
            return true;
        }
        String m = ex.getMessage();
        return m != null && m.toLowerCase().contains("already exists");
    }

    private static boolean isMissingTsColumn(Schema.Dialect dialect, SQLException ex) {
        if (dialect == Schema.Dialect.MYSQL && ex.getErrorCode() == 1072) {
            return true;
        }
        String m = ex.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ts") && (
                lower.contains("no such column")
                || lower.contains("unknown column")
                || lower.contains("does not exist")
                || lower.contains("doesn't exist"));
    }

    @Override
    public String label() {
        return "V9TsIdIndex (v8→v9, vg_actions_ts_id composite index)";
    }
}
