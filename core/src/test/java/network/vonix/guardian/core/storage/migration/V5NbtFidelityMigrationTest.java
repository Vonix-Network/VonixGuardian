package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X1 regression: {@link V5NbtFidelity} migrates a v4 install in place,
 * five new nullable NBT columns appear on {@code vg_actions}, existing row
 * data is preserved, and the migration is idempotent under the MySQL
 * DDL-autocommit crash window.
 *
 * <p>SQLite substrate; the {@code ALTER TABLE ADD COLUMN} shape is common
 * across all three dialects for the nullable-column case here, and the
 * per-dialect BLOB keyword ({@code LONGBLOB / BYTEA / BLOB}) is exercised in
 * the {@link Schema} DDL tests.
 */
class V5NbtFidelityMigrationTest {

    @Test
    void v4_install_gets_upgraded_to_v5_and_columns_appear() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            // Simulate a v4 install: schema_version stamped at 4, vg_actions
            // carrying the pre-v5 column list plus sign metadata. Insert a
            // representative row so we can verify data is preserved.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (4, 0)");
                st.execute("CREATE TABLE vg_actions ("
                        + "id INTEGER PRIMARY KEY, "
                        + "ts BIGINT NOT NULL, "
                        + "type SMALLINT NOT NULL, "
                        + "user_id INTEGER NOT NULL, "
                        + "world_id INTEGER NOT NULL, "
                        + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                        + "target VARCHAR(4096) NOT NULL, "
                        + "meta TEXT NULL, "
                        + "amount INTEGER NOT NULL DEFAULT 1, "
                        + "rolled_back TINYINT NOT NULL DEFAULT 0, "
                        + "source_tag VARCHAR(64) NULL, "
                        + "sign_side VARCHAR(8) NULL, "
                        + "sign_dye_color VARCHAR(16) NULL, "
                        + "sign_waxed BOOLEAN NULL)");
                st.execute("INSERT INTO vg_actions(id, ts, type, user_id, world_id, x, y, z, target) "
                        + "VALUES (1, 12345, 1, 1, 1, 10, 64, 20, 'minecraft:oak_fence')");
            }

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(4);

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
            assertThat(Schema.CURRENT_VERSION).as("v1.3.1 X1 targets schema v5").isEqualTo(5);

            Set<String> cols = columnsOf(c);
            assertThat(cols).contains(
                "old_block_state", "new_block_state",
                "block_entity_nbt", "item_nbt", "entity_nbt");

            // Existing row still intact after the additive ALTER pass.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT id, target, old_block_state, new_block_state, "
                   + "block_entity_nbt, item_nbt, entity_nbt FROM vg_actions WHERE id=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1L);
                assertThat(rs.getString("target")).isEqualTo("minecraft:oak_fence");
                // Pre-existing row has NULL for all v5 columns — no back-fill.
                assertThat(rs.getString("old_block_state")).isNull();
                assertThat(rs.getString("new_block_state")).isNull();
                assertThat(rs.getBytes("block_entity_nbt")).isNull();
                assertThat(rs.getBytes("item_nbt")).isNull();
                assertThat(rs.getBytes("entity_nbt")).isNull();
            }
        }
    }

    @Test
    void migration_is_reentrant_when_columns_already_exist() throws Exception {
        // Simulate the MySQL-DDL-autocommit crash window: schema advanced
        // (some or all NBT columns present) but version stamp still says v4.
        // Re-running must succeed without throwing on the duplicate-column
        // error, and land at v5.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (4, 0)");
                st.execute("CREATE TABLE vg_actions ("
                        + "id INTEGER PRIMARY KEY, target VARCHAR(4096) NOT NULL, "
                        + "old_block_state TEXT NULL, "
                        + "new_block_state TEXT NULL, "
                        + "block_entity_nbt BLOB NULL, "
                        + "item_nbt BLOB NULL, "
                        + "entity_nbt BLOB NULL)");
            }
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
        }
    }

    @Test
    void fresh_install_ddl_carries_v5_columns_and_stamps_current() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Schema.createTables(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
            Set<String> cols = columnsOf(c);
            assertThat(cols).contains(
                "old_block_state", "new_block_state",
                "block_entity_nbt", "item_nbt", "entity_nbt");
        }
    }

    @Test
    void mysql_ddl_declares_longblob_and_postgres_declares_bytea() {
        // We can't stand up a MySQL/Postgres server in a unit test, but we can
        // assert the DDL text carries the right per-dialect BLOB keyword — the
        // choice matters because MEDIUMBLOB caps at 16 MB (fine today, tight
        // tomorrow) and BYTEA vs BLOB is the difference between Postgres
        // accepting the CREATE TABLE and rejecting it.
        String mysql = String.join("\n", Schema.tableDdlFor(Schema.Dialect.MYSQL));
        assertThat(mysql).contains("block_entity_nbt LONGBLOB");
        assertThat(mysql).contains("item_nbt LONGBLOB");
        assertThat(mysql).contains("entity_nbt LONGBLOB");

        String pg = String.join("\n", Schema.tableDdlFor(Schema.Dialect.POSTGRES));
        assertThat(pg).contains("block_entity_nbt BYTEA");
        assertThat(pg).contains("item_nbt BYTEA");
        assertThat(pg).contains("entity_nbt BYTEA");

        String sqlite = String.join("\n", Schema.tableDdlFor(Schema.Dialect.SQLITE));
        assertThat(sqlite).contains("block_entity_nbt BLOB");
    }

    private static Set<String> columnsOf(Connection c) throws Exception {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(vg_actions)")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }
}
