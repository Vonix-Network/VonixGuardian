package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link V4SignMetadata}: verify that a v3 install is upgraded
 * to v4 in place, three new sign-metadata columns are present after the run,
 * and existing row data is preserved.
 *
 * <p>SQLite is the substrate — sufficient because {@code ALTER TABLE ADD COLUMN}
 * is the same across all three dialects for the nullable-column case this
 * migration exercises.
 */
class V4SignMetadataMigrationTest {

    @Test
    void v3_install_gets_upgraded_to_v4_and_columns_appear() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            // Simulate a v3 install: schema_version stamped at 3, and a
            // vg_actions table with the pre-v4 column list. Insert one row so
            // we can verify data is preserved.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (3, 0)");
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
                        + "source_tag VARCHAR(64) NULL)");
                st.execute("INSERT INTO vg_actions(id, ts, type, user_id, world_id, x, y, z, target) "
                        + "VALUES (1, 12345, 11, 1, 1, 10, 64, 20, 'Line 1\\nLine 2\\nLine 3\\nLine 4')");
            }

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(3);

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);

            // Verify the three new columns exist (PRAGMA table_info reports them).
            boolean hasSide = false, hasDye = false, hasWaxed = false;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(vg_actions)")) {
                while (rs.next()) {
                    String col = rs.getString("name");
                    if ("sign_side".equals(col)) hasSide = true;
                    if ("sign_dye_color".equals(col)) hasDye = true;
                    if ("sign_waxed".equals(col)) hasWaxed = true;
                }
            }
            assertThat(hasSide).as("sign_side column present after v4").isTrue();
            assertThat(hasDye).as("sign_dye_color column present after v4").isTrue();
            assertThat(hasWaxed).as("sign_waxed column present after v4").isTrue();

            // Existing row still intact.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT id, target, sign_side, sign_dye_color, sign_waxed FROM vg_actions WHERE id=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(1L);
                assertThat(rs.getString("target")).contains("Line 1");
                assertThat(rs.getString("sign_side")).isNull();
                assertThat(rs.getString("sign_dye_color")).isNull();
                rs.getBoolean("sign_waxed");
                assertThat(rs.wasNull()).isTrue();
            }
        }
    }

    @Test
    void migration_is_reentrant_when_columns_already_exist() throws Exception {
        // Simulate the MySQL-DDL-autocommit crash window: schema already advanced
        // (columns present) but version stamp still says v3. Re-running must
        // succeed without throwing on the duplicate-column error, and land at v4.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (3, 0)");
                st.execute("CREATE TABLE vg_actions ("
                        + "id INTEGER PRIMARY KEY, target VARCHAR(4096) NOT NULL, "
                        + "sign_side VARCHAR(8) NULL, "
                        + "sign_dye_color VARCHAR(16) NULL, "
                        + "sign_waxed BOOLEAN NULL)");
            }
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
        }
    }
}
