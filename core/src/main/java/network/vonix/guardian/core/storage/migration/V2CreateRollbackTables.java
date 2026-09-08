package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration v1 → v2: additive rollback-batch audit tables.
 *
 * <p>Idempotent {@code CREATE TABLE IF NOT EXISTS} so a catalog that already
 * received the tables from {@link Schema#createTables} can still stamp v2.
 */
public final class V2CreateRollbackTables implements Migration {

    @Override public int fromVersion() { return 1; }
    @Override public int toVersion() { return 2; }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        String pk = switch (dialect) {
            case SQLITE   -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL    -> "BIGINT PRIMARY KEY AUTO_INCREMENT";
            case POSTGRES -> "BIGSERIAL PRIMARY KEY";
        };
        String tiny = dialect == Schema.Dialect.POSTGRES ? "SMALLINT" : "TINYINT";
        String text = "TEXT";
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS vg_rollback_batches ("
                    + "id " + pk + ", "
                    + "ts BIGINT NOT NULL, "
                    + "actor_uuid CHAR(36) NULL, "
                    + "mode SMALLINT NOT NULL, "
                    + "affected INTEGER NOT NULL, "
                    + "completed " + tiny + " NOT NULL DEFAULT 0, "
                    + "filter_json " + text + " NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS vg_rollback_batch_actions ("
                    + "batch_id INTEGER NOT NULL, "
                    + "action_id INTEGER NOT NULL, "
                    + "PRIMARY KEY (batch_id, action_id))");
            String index = dialect == Schema.Dialect.MYSQL
                    ? "CREATE INDEX vg_rollback_batches_ts ON vg_rollback_batches(ts)"
                    : "CREATE INDEX IF NOT EXISTS vg_rollback_batches_ts ON vg_rollback_batches(ts)";
            try {
                st.execute(index);
            } catch (SQLException ex) {
                if (dialect != Schema.Dialect.MYSQL || ex.getErrorCode() != 1061) {
                    String m = ex.getMessage();
                    if (m == null || !m.toLowerCase().contains("already exists")) {
                        throw ex;
                    }
                }
            }
        }
    }

    @Override
    public String label() {
        return "V2CreateRollbackTables (v1→v2, vg_rollback_batches)";
    }
}
