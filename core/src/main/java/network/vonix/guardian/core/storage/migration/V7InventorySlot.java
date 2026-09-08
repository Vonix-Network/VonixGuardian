package network.vonix.guardian.core.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import network.vonix.guardian.core.storage.Schema;

/** Schema migration v6 → v7: add nullable exact player-inventory slot identity. */
public final class V7InventorySlot implements Migration {
    @Override public int fromVersion() { return 6; }
    @Override public int toVersion() { return 7; }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        try (Statement st = c.createStatement()) {
            try {
                st.execute("ALTER TABLE vg_actions ADD COLUMN inventory_slot INTEGER NULL");
            } catch (SQLException ex) {
                if (!isDuplicateColumn(dialect, ex)) throw ex;
            }
        }
    }

    private static final int MYSQL_ERR_DUP_FIELDNAME = 1060;
    private static final String PG_SQLSTATE_DUP_COLUMN = "42701";

    private static boolean isDuplicateColumn(Schema.Dialect dialect, SQLException ex) {
        if (dialect == Schema.Dialect.MYSQL && ex.getErrorCode() == MYSQL_ERR_DUP_FIELDNAME) {
            return true;
        }
        if (dialect == Schema.Dialect.POSTGRES && PG_SQLSTATE_DUP_COLUMN.equals(ex.getSQLState())) {
            return true;
        }
        String message = ex.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("duplicate column")
            || lower.contains("duplicate column name")
            || (lower.contains("already exists") && lower.contains("inventory_slot"))
            || (lower.contains("duplicate") && lower.contains("inventory_slot"));
    }
}
