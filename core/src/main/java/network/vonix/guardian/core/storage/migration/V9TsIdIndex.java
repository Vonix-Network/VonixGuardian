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
                if (!isDuplicateIndex(dialect, ex)) {
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

    @Override
    public String label() {
        return "V9TsIdIndex (v8→v9, vg_actions_ts_id composite index)";
    }
}
