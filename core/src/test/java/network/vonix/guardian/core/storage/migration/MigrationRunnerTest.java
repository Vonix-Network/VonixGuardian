package network.vonix.guardian.core.storage.migration;

import network.vonix.guardian.core.storage.Schema;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MigrationRunner}: idempotency, no-op-on-current, and
 * correct advance across the recorded {@code vg_schema_version} entry.
 *
 * <p>SQLite is used as the substrate because {@link V3WidenActionTarget} is a
 * documented no-op on SQLite (TEXT affinity), which lets us exercise the runner
 * mechanics — read version, walk chain, stamp forward — without needing a
 * MySQL/Postgres testcontainer.
 */
class MigrationRunnerTest {

    @Test
    void migrate_advances_v2_install_to_current() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            // Simulate an existing v2 install: stamp v2 manually, and provide a
            // stub vg_actions so the ALTER (no-op on SQLite) has a target.
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (2, 0)");
                st.execute("CREATE TABLE vg_actions (id INTEGER PRIMARY KEY, target VARCHAR(192) NOT NULL)");
            }

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(2);

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);

            assertThat(MigrationRunner.readVersion(c)).isEqualTo(Schema.CURRENT_VERSION);
        }
    }

    @Test
    void migrate_is_idempotent_when_already_at_current_version() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES ("
                        + Schema.CURRENT_VERSION + ", 0)");
            }
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);

            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM vg_schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void default_migration_chain_is_contiguous() {
        // Constructor enforces single-step contract; this just proves it doesn't throw.
        MigrationRunner.defaults();
    }
}
