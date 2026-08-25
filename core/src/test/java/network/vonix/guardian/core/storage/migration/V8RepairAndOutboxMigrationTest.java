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

class V8RepairAndOutboxMigrationTest {

    @Test
    void v7InstallGainsRepairAndOutboxTables() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (7, 0)");
                st.execute("CREATE TABLE vg_actions (id INTEGER PRIMARY KEY, target VARCHAR(4096) NOT NULL)");
            }

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(8);
            assertThat(tablesOf(c)).contains("vg_repair_required", "vg_sink_outbox");

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
        }
    }

    private static Set<String> tablesOf(Connection c) throws Exception {
        Set<String> out = new HashSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
