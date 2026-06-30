package network.vonix.guardian.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialect-aware DDL for the VonixGuardian schema.
 *
 * <p>The canonical schema lives in {@code SHARED-CONTRACTS.md} § 3.1. This class substitutes
 * the per-dialect auto-increment specifier and applies the DDL idempotently, recording the
 * applied version in {@code vg_schema_version}.
 *
 * <p>Versions:
 * <ul>
 *   <li><b>v1</b> — initial: {@code vg_users}, {@code vg_worlds}, {@code vg_actions}, indexes.</li>
 *   <li><b>v2</b> — additive: {@code vg_rollback_batches} + {@code vg_rollback_batch_actions}
 *       (crash-recovery audit for rollback/restore operations). Migration is purely additive
 *       (CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS), so it is safe to run on a
 *       fresh database or an existing v1 database without any data movement.</li>
 * </ul>
 */
public final class Schema {

    /** Current schema version. */
    public static final int CURRENT_VERSION = 2;

    /** SQL dialect — primarily affects auto-increment and a couple of column types. */
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES
    }

    private Schema() {}

    /** MySQL error code for "Duplicate key name" (ER_DUP_KEYNAME). */
    private static final int MYSQL_ERR_DUP_KEYNAME = 1061;

    /**
     * Create all tables + indexes for the given dialect, then stamp the schema_version
     * table. Safe to call repeatedly.
     *
     * <p><b>Dialect note:</b> {@code CREATE INDEX IF NOT EXISTS} is supported by SQLite and
     * PostgreSQL but <i>not</i> by MySQL (MariaDB does accept it as an extension). For MySQL
     * we issue the bare {@code CREATE INDEX} and swallow error code 1061 (duplicate key name),
     * which is the idiomatic MySQL way to make index creation idempotent without taking
     * locks on {@code information_schema}.
     */
    public static void createTables(Connection c, Dialect dialect) throws SQLException {
        // 1. Tables — every CREATE TABLE uses IF NOT EXISTS, which all three dialects accept.
        try (Statement st = c.createStatement()) {
            for (String ddl : tableDdlFor(dialect)) {
                st.execute(ddl);
            }
        }
        // 2. Indexes — dialect-specific idempotency strategy.
        createIndexesIdempotent(c, dialect, indexDdlFor(dialect));
        // 3. Version stamp.
        stampVersion(c, CURRENT_VERSION);
    }

    /**
     * Returns the full ordered DDL list (tables + indexes) for the given dialect. Kept for
     * backward compatibility with tests and tooling that want a single flat list.
     *
     * <p><b>Caveat:</b> the index statements in this list use {@code CREATE INDEX IF NOT EXISTS}
     * which is unsafe on MySQL. {@link #createTables(Connection, Dialect)} bypasses this list
     * and applies indexes via {@link #createIndexesIdempotent}. Callers that execute the list
     * verbatim against MySQL must catch {@code SQLException} with error code 1061.
     */
    public static List<String> ddlFor(Dialect d) {
        List<String> out = new ArrayList<>(tableDdlFor(d));
        out.addAll(indexDdlFor(d));
        out.add(schemaVersion(d));
        return List.copyOf(out);
    }

    /** Table-only DDL (tables + schema_version table), no indexes. Safe on all dialects. */
    public static List<String> tableDdlFor(Dialect d) {
        List<String> out = new ArrayList<>();
        // --- v1 ---
        out.add(users(d));
        out.add(worlds(d));
        out.add(actions(d));
        // --- v2 (additive) ---
        out.add(rollbackBatches(d));
        out.add(rollbackBatchActions(d));
        // --- schema_version table ---
        out.add(schemaVersion(d));
        return List.copyOf(out);
    }

    /**
     * Index-only DDL. For SQLite and PostgreSQL the statements include {@code IF NOT EXISTS}.
     * For MySQL they do not — the idempotency is handled by
     * {@link #createIndexesIdempotent(Connection, Dialect, List)} catching error 1061.
     */
    public static List<String> indexDdlFor(Dialect d) {
        String prefix = (d == Dialect.MYSQL) ? "CREATE INDEX " : "CREATE INDEX IF NOT EXISTS ";
        List<String> out = new ArrayList<>();
        out.add(prefix + "vg_actions_pos    ON vg_actions(world_id, x, z, y, ts)");
        out.add(prefix + "vg_actions_user_t ON vg_actions(user_id, ts)");
        out.add(prefix + "vg_actions_type_t ON vg_actions(type, ts)");
        out.add(prefix + "vg_actions_ts     ON vg_actions(ts)");
        out.add(prefix + "vg_rollback_batches_ts ON vg_rollback_batches(ts)");
        return List.copyOf(out);
    }

    /**
     * Apply a batch of CREATE INDEX statements idempotently. For MySQL we swallow error
     * code 1061 (duplicate key name); for other dialects the statements already contain
     * {@code IF NOT EXISTS} so any error is real and rethrown.
     */
    private static void createIndexesIdempotent(Connection c, Dialect dialect, List<String> indexDdl)
            throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String ddl : indexDdl) {
                try {
                    st.execute(ddl);
                } catch (SQLException e) {
                    if (dialect == Dialect.MYSQL && e.getErrorCode() == MYSQL_ERR_DUP_KEYNAME) {
                        // Index already exists — idempotent no-op on MySQL.
                        continue;
                    }
                    throw e;
                }
            }
        }
    }

    private static String pk(Dialect d) {
        return switch (d) {
            case SQLITE   -> "INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL    -> "BIGINT PRIMARY KEY AUTO_INCREMENT";
            case POSTGRES -> "BIGSERIAL PRIMARY KEY";
        };
    }

    private static String textType(Dialect d) {
        // TEXT is fine on all three, but MySQL prefers it without length.
        return "TEXT";
    }

    private static String tinyint(Dialect d) {
        return d == Dialect.POSTGRES ? "SMALLINT" : "TINYINT";
    }

    private static String users(Dialect d) {
        return "CREATE TABLE IF NOT EXISTS vg_users ("
            + "id " + pk(d) + ", "
            + "uuid CHAR(36) NULL, "
            + "name VARCHAR(64) NOT NULL, "
            + "first_seen BIGINT NOT NULL, "
            + "last_seen BIGINT NOT NULL, "
            + "UNIQUE(uuid), "
            + "UNIQUE(name)"
            + ")";
    }

    private static String worlds(Dialect d) {
        // "key" is reserved in MySQL; quote with the portable double-quote (MySQL needs
        // ANSI_QUOTES) — we use a non-reserved alias instead to keep things portable.
        return "CREATE TABLE IF NOT EXISTS vg_worlds ("
            + "id " + pk(d) + ", "
            + "world_key VARCHAR(96) NOT NULL UNIQUE"
            + ")";
    }

    private static String actions(Dialect d) {
        return "CREATE TABLE IF NOT EXISTS vg_actions ("
            + "id " + pk(d) + ", "
            + "ts BIGINT NOT NULL, "
            + "type SMALLINT NOT NULL, "
            + "user_id INTEGER NOT NULL, "
            + "world_id INTEGER NOT NULL, "
            + "x INTEGER NOT NULL, "
            + "y INTEGER NOT NULL, "
            + "z INTEGER NOT NULL, "
            + "target VARCHAR(192) NOT NULL, "
            + "meta " + textType(d) + " NULL, "
            + "amount INTEGER NOT NULL DEFAULT 1, "
            + "rolled_back " + tinyint(d) + " NOT NULL DEFAULT 0, "
            + "source_tag VARCHAR(64) NULL"
            + ")";
    }

    private static String rollbackBatches(Dialect d) {
        return "CREATE TABLE IF NOT EXISTS vg_rollback_batches ("
            + "id " + pk(d) + ", "
            + "ts BIGINT NOT NULL, "
            + "actor_uuid CHAR(36) NULL, "
            + "mode SMALLINT NOT NULL, "
            + "affected INTEGER NOT NULL, "
            + "completed " + tinyint(d) + " NOT NULL DEFAULT 0, "
            + "filter_json " + textType(d) + " NULL"
            + ")";
    }

    private static String rollbackBatchActions(Dialect d) {
        return "CREATE TABLE IF NOT EXISTS vg_rollback_batch_actions ("
            + "batch_id INTEGER NOT NULL, "
            + "action_id INTEGER NOT NULL, "
            + "PRIMARY KEY (batch_id, action_id)"
            + ")";
    }

    private static String schemaVersion(Dialect d) {
        return "CREATE TABLE IF NOT EXISTS vg_schema_version ("
            + "version INTEGER PRIMARY KEY, "
            + "applied_at BIGINT NOT NULL"
            + ")";
    }

    /**
     * Insert version if not already present. Uses a dialect-portable derived-table form
     * so MySQL (which rejects {@code SELECT ... WHERE NOT EXISTS} without a {@code FROM})
     * works alongside SQLite and PostgreSQL.
     */
    public static void stampVersion(Connection c, int version) throws SQLException {
        // The "SELECT ?, ?" form lacks a FROM clause, which MySQL refuses when combined with
        // WHERE. Wrap it in a derived table so all three dialects accept the statement.
        try (var ps = c.prepareStatement(
                "INSERT INTO vg_schema_version(version, applied_at) "
              + "SELECT v, a FROM (SELECT ? AS v, ? AS a) AS src "
              + "WHERE NOT EXISTS (SELECT 1 FROM vg_schema_version WHERE version = ?)")) {
            ps.setInt(1, version);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, version);
            ps.executeUpdate();
        }
    }
}
