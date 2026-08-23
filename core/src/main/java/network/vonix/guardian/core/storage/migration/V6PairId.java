package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration <b>v5 → v6</b>: add nullable {@code pair_id BIGINT} on
 * {@code vg_actions} and an index for paired-rollback sibling lookup.
 *
 * <p>The pairing token previously lived only in {@code FireCauserMemory},
 * which is process-local and TTL-bounded. Without a durable column, a
 * restart (or a rollback issued after the memory expired) could no longer
 * join an entity block-change with the fire it caused.</p>
 *
 * <p>{@code NULL} means unpaired. Fresh installs get the column from
 * {@link Schema} DDL; existing v5 databases are upgraded here. The
 * per-dialect "column already exists" / "duplicate key name" errors are
 * swallowed so a partially applied MySQL DDL-autocommit crash window is
 * safely re-runnable.</p>
 */
public final class V6PairId implements Migration {

    private static final int MYSQL_ERR_DUP_FIELDNAME = 1060;
    private static final int MYSQL_ERR_DUP_KEYNAME = 1061;
    private static final String PG_SQLSTATE_DUP_COLUMN = "42701";
    private static final String PG_SQLSTATE_DUP_TABLE = "42P07";

    @Override
    public int fromVersion() {
        return 5;
    }

    @Override
    public int toVersion() {
        return 6;
    }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        try (Statement st = c.createStatement()) {
            try {
                st.execute("ALTER TABLE vg_actions ADD COLUMN pair_id BIGINT NULL");
            } catch (SQLException ex) {
                if (!isDuplicateColumn(dialect, ex)) {
                    throw ex;
                }
            }
            String indexDdl = dialect == Schema.Dialect.MYSQL
                    ? "CREATE INDEX vg_actions_pair ON vg_actions(pair_id)"
                    : "CREATE INDEX IF NOT EXISTS vg_actions_pair ON vg_actions(pair_id)";
            try {
                st.execute(indexDdl);
            } catch (SQLException ex) {
                if (!isDuplicateIndex(dialect, ex)) {
                    throw ex;
                }
            }
        }
    }

    private static boolean isDuplicateColumn(Schema.Dialect dialect, SQLException ex) {
        return switch (dialect) {
            case MYSQL    -> ex.getErrorCode() == MYSQL_ERR_DUP_FIELDNAME;
            case POSTGRES -> PG_SQLSTATE_DUP_COLUMN.equals(ex.getSQLState());
            case SQLITE   -> {
                String m = ex.getMessage();
                yield m != null && m.toLowerCase().contains("duplicate column");
            }
        };
    }

    private static boolean isDuplicateIndex(Schema.Dialect dialect, SQLException ex) {
        return switch (dialect) {
            case MYSQL    -> ex.getErrorCode() == MYSQL_ERR_DUP_KEYNAME;
            case POSTGRES -> PG_SQLSTATE_DUP_TABLE.equals(ex.getSQLState())
                    || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("already exists"));
            case SQLITE   -> {
                String m = ex.getMessage();
                yield m != null && m.toLowerCase().contains("already exists");
            }
        };
    }

    @Override
    public String label() {
        return "V6PairId (v5→v6, add vg_actions.pair_id + vg_actions_pair index)";
    }
}
