package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration v7 → v8: durable repair-required rows and the JDBC/JSONL
 * sink outbox. Both tables are additive {@code CREATE TABLE IF NOT EXISTS}.
 */
public final class V8RepairAndOutbox implements Migration {

    @Override public int fromVersion() { return 7; }
    @Override public int toVersion() { return 8; }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        String text = dialect == Schema.Dialect.POSTGRES ? "TEXT" : "TEXT";
        String blob = switch (dialect) {
            case MYSQL    -> "LONGBLOB";
            case POSTGRES -> "BYTEA";
            case SQLITE   -> "BLOB";
        };
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS vg_repair_required ("
                    + "action_id INTEGER PRIMARY KEY, "
                    + "pair_id BIGINT NULL, "
                    + "batch_id INTEGER NULL, "
                    + "reason " + text + " NOT NULL, "
                    + "ts BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS vg_sink_outbox ("
                    + "id INTEGER PRIMARY KEY, "
                    + "payload " + blob + " NOT NULL, "
                    + "created_ts BIGINT NOT NULL)");
        }
    }

    @Override
    public String label() {
        return "V8RepairAndOutbox (v7→v8, vg_repair_required + vg_sink_outbox)";
    }
}
