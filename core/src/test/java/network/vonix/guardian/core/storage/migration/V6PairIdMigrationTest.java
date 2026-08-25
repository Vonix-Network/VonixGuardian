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
 * Schema v6: {@code pair_id} is added in place on a v5 install, existing rows
 * stay unpaired (NULL), and the migration is re-runnable.
 */
class V6PairIdMigrationTest {

    @Test
    void v5_install_gets_upgraded_to_v6_and_pair_id_appears() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (5, 0)");
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
                        + "sign_waxed BOOLEAN NULL, "
                        + "old_block_state TEXT NULL, "
                        + "new_block_state TEXT NULL, "
                        + "block_entity_nbt BLOB NULL, "
                        + "item_nbt BLOB NULL, "
                        + "entity_nbt BLOB NULL)");
                st.execute("INSERT INTO vg_actions(id, ts, type, user_id, world_id, x, y, z, target) "
                        + "VALUES (1, 12345, 1, 1, 1, 10, 64, 20, 'minecraft:oak_log')");
            }

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(5);
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
            assertThat(Schema.CURRENT_VERSION).isGreaterThanOrEqualTo(6);
            assertThat(columnsOf(c)).contains("pair_id");

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, target, pair_id FROM vg_actions WHERE id=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("target")).isEqualTo("minecraft:oak_log");
                assertThat(rs.getObject("pair_id")).isNull();
            }
        }
    }

    @Test
    void migration_is_reentrant_when_column_already_exists() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (5, 0)");
                st.execute("CREATE TABLE vg_actions (id INTEGER PRIMARY KEY, target VARCHAR(4096) NOT NULL, "
                        + "pair_id BIGINT NULL)");
                st.execute("CREATE INDEX vg_actions_pair ON vg_actions(pair_id)");
            }
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
        }
    }

    @Test
    void fresh_install_ddl_carries_pair_id_and_stamps_current() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Schema.createTables(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
            assertThat(columnsOf(c)).contains("pair_id");
        }
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
