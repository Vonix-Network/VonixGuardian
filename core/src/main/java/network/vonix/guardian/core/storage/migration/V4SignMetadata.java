package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema migration <b>v3 → v4</b>: add three nullable columns to
 * {@code vg_actions} carrying CoreProtect-v24-compatible sign metadata:
 * {@code sign_side VARCHAR(8)}, {@code sign_dye_color VARCHAR(16)},
 * {@code sign_waxed BOOLEAN}.
 *
 * <h2>Why</h2>
 * CoreProtect's 1.20+ sign lookup returns three fields VG previously did not
 * persist — front/back side, dye color, and the {@code waxed} flag. VG was
 * cramming only the joined lines into {@code target}. This migration widens the
 * schema so the {@code /vg l a:sign} formatter can render the same rich context
 * ("front side, red, waxed") without lying about missing information.
 *
 * <h2>Dialect handling</h2>
 * All three dialects accept the {@code ALTER TABLE vg_actions ADD COLUMN}
 * form for nullable columns without a table rewrite:
 * <ul>
 *   <li><b>MySQL / MariaDB:</b> {@code ALTER TABLE ... ADD COLUMN c TYPE NULL}
 *       is an instant, no-copy metadata operation on 8.x for a trailing
 *       nullable column.</li>
 *   <li><b>PostgreSQL:</b> adding a nullable column without a {@code DEFAULT}
 *       is a metadata-only catalogue update (no table scan) since 11+.</li>
 *   <li><b>SQLite:</b> {@code ALTER TABLE ... ADD COLUMN} for a new nullable
 *       column has always been O(1) — a single sqlite_schema row rewrite.
 *       BOOLEAN maps to INTEGER affinity.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * The {@link MigrationRunner} only invokes {@link #apply(Connection, Schema.Dialect)}
 * when the recorded schema version is exactly 3, so we do not have to introspect
 * {@code information_schema} to detect a partially-applied v4. If the ALTER
 * fails mid-flight the transaction rolls back (Postgres) or MySQL commits the
 * DDL implicitly before the version stamp — a subsequent restart re-runs the
 * ALTER against an already-widened column, which MySQL treats as an error we
 * cannot silently swallow. To keep the rerun safe we issue the three ALTERs
 * separately and swallow the MySQL "duplicate column" error 1060 on each one
 * (the analogous {@code ERROR: column ... already exists} on Postgres has
 * SQLSTATE 42701, and SQLite reports {@code duplicate column name} with a
 * message match). This mirrors the {@code createIndexesIdempotent} pattern
 * already established in {@link Schema}.
 */
public final class V4SignMetadata implements Migration {

    /** MySQL: {@code ER_DUP_FIELDNAME} — column already exists. */
    private static final int MYSQL_ERR_DUP_FIELDNAME = 1060;
    /** PostgreSQL SQLSTATE for "duplicate column". */
    private static final String PG_SQLSTATE_DUP_COLUMN = "42701";

    @Override
    public int fromVersion() {
        return 3;
    }

    @Override
    public int toVersion() {
        return 4;
    }

    @Override
    public void apply(Connection c, Schema.Dialect dialect) throws SQLException {
        // BOOLEAN keyword is accepted by all three dialects; SQLite treats it as
        // NUMERIC affinity, MySQL as TINYINT(1), Postgres as its native BOOLEAN.
        String[] ddls = new String[] {
            "ALTER TABLE vg_actions ADD COLUMN sign_side VARCHAR(8) NULL",
            "ALTER TABLE vg_actions ADD COLUMN sign_dye_color VARCHAR(16) NULL",
            "ALTER TABLE vg_actions ADD COLUMN sign_waxed BOOLEAN NULL",
        };
        try (Statement st = c.createStatement()) {
            for (String ddl : ddls) {
                try {
                    st.execute(ddl);
                } catch (SQLException ex) {
                    if (isDuplicateColumn(dialect, ex)) {
                        // Column was added by a previous partial run — safe to skip.
                        continue;
                    }
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

    @Override
    public String label() {
        return "V4SignMetadata (v3→v4, add vg_actions.sign_side/sign_dye_color/sign_waxed)";
    }
}
