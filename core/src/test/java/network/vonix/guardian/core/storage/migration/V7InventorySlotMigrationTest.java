package network.vonix.guardian.core.storage.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import network.vonix.guardian.core.storage.Schema;
import org.junit.jupiter.api.Test;

/** Schema v7 adds nullable exact player-inventory slot identity. */
class V7InventorySlotMigrationTest {
    @Test
    void v6_install_gets_upgraded_and_slot_is_nullable_and_reentrant() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (6, 0)");
                st.execute("CREATE TABLE vg_actions (id INTEGER PRIMARY KEY, target VARCHAR(4096) NOT NULL, pair_id BIGINT NULL)");
            }

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
            assertThat(Schema.CURRENT_VERSION).isGreaterThanOrEqualTo(7);
            assertThat(columnsOf(c)).contains("inventory_slot");

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
        }
    }

    private static Set<String> columnsOf(Connection c) throws Exception {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(vg_actions)")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }
}
