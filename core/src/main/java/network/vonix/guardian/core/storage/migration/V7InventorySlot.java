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

    private static boolean isDuplicateColumn(Schema.Dialect dialect, SQLException ex) {
        String message = ex.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("duplicate column")
            || lower.contains("duplicate column name")
            || lower.contains("already exists")
            || lower.contains("duplicate") && lower.contains("inventory_slot")
            || dialect == Schema.Dialect.POSTGRES && lower.contains("duplicate") && lower.contains("column");
    }
}
