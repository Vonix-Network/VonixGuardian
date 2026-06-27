package network.vonix.guardian.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Dialect-aware DDL for the VonixGuardian schema (v1).
 *
 * <p>The canonical schema lives in {@code SHARED-CONTRACTS.md} § 3.1. This class substitutes
 * the per-dialect auto-increment specifier and applies the DDL idempotently, recording the
 * applied version in {@code vg_schema_version}.
 */
public final class Schema {

    /** Current schema version. */
    public static final int CURRENT_VERSION = 1;

    /** SQL dialect — primarily affects auto-increment and a couple of column types. */
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES
    }

    private Schema() {}

    /**
     * Create all v1 tables + indexes for the given dialect, then stamp the schema_version
     * table. Safe to call repeatedly — every statement is {@code IF NOT EXISTS}.
     */
    public static void createTables(Connection c, Dialect dialect) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String ddl : ddlFor(dialect)) {
                st.execute(ddl);
            }
        }
        stampVersion(c, CURRENT_VERSION);
    }

    /** Return the ordered DDL statements for the given dialect. */
    public static List<String> ddlFor(Dialect d) {
        return List.of(
            users(d),
            worlds(d),
            actions(d),
            "CREATE INDEX IF NOT EXISTS vg_actions_pos    ON vg_actions(world_id, x, z, y, ts)",
            "CREATE INDEX IF NOT EXISTS vg_actions_user_t ON vg_actions(user_id, ts)",
            "CREATE INDEX IF NOT EXISTS vg_actions_type_t ON vg_actions(type, ts)",
            "CREATE INDEX IF NOT EXISTS vg_actions_ts     ON vg_actions(ts)",
            schemaVersion(d)
        );
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

    private static String schemaVersion(Dialect d) {
        return "CREATE TABLE IF NOT EXISTS vg_schema_version ("
            + "version INTEGER PRIMARY KEY, "
            + "applied_at BIGINT NOT NULL"
            + ")";
    }

    /** Insert version if not already present. */
    public static void stampVersion(Connection c, int version) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO vg_schema_version(version, applied_at) "
              + "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM vg_schema_version WHERE version = ?)")) {
            ps.setInt(1, version);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, version);
            ps.executeUpdate();
        }
    }
}
