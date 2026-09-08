package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import network.vonix.guardian.core.storage.migration.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disposable SQLite fixtures for every supported historical schema. No live
 * database is touched.
 */
class HistoricalSchemaCompatibilityTest {

    @Test
    void emptyVersionTableNextToOldActionsIsNotStampedCurrent() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("CREATE TABLE vg_users (id INTEGER PRIMARY KEY, uuid CHAR(36) NULL, name VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL)");
                st.execute("CREATE TABLE vg_worlds (id INTEGER PRIMARY KEY, world_key VARCHAR(96) NOT NULL UNIQUE)");
                st.execute(v2ActionsDdl());
                st.execute("INSERT INTO vg_users(id, uuid, name, first_seen, last_seen) VALUES (1, NULL, 'null', 1, 1)");
                st.execute("INSERT INTO vg_worlds(id, world_key) VALUES (1, 'minecraft:overworld')");
                st.execute("INSERT INTO vg_actions(id, ts, type, user_id, world_id, x, y, z, target, meta, amount, rolled_back, source_tag) "
                        + "VALUES (7, 100, 1, 1, 1, 0, 64, 0, '" + "t".repeat(200) + "', NULL, 1, 0, NULL)");
            }

            Schema.createTables(c, Schema.Dialect.SQLITE);

            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT MAX(version) FROM vg_schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("empty version table + old fact table must not stamp current")
                        .isEqualTo(2);
            }

            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertUsableCurrentSchema(c, 7L, "t".repeat(200));
        }
    }

    @Test
    void stampedV1AdvancesToCurrentWithoutLosingRow() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (1, 0)");
                st.execute("CREATE TABLE vg_users (id INTEGER PRIMARY KEY, uuid CHAR(36) NULL, name VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL)");
                st.execute("CREATE TABLE vg_worlds (id INTEGER PRIMARY KEY, world_key VARCHAR(96) NOT NULL UNIQUE)");
                st.execute(v2ActionsDdl());
                st.execute("INSERT INTO vg_users(id, uuid, name, first_seen, last_seen) VALUES (1, NULL, 'legacy', 1, 1)");
                st.execute("INSERT INTO vg_worlds(id, world_key) VALUES (1, 'minecraft:overworld')");
                st.execute("INSERT INTO vg_actions(id, ts, type, user_id, world_id, x, y, z, target, meta, amount, rolled_back, source_tag) "
                        + "VALUES (3, 50, 2, 1, 1, 1, 64, 1, 'minecraft:dirt', NULL, 1, 0, NULL)");
            }
            Schema.createTables(c, Schema.Dialect.SQLITE);
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            assertUsableCurrentSchema(c, 3L, "minecraft:dirt");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 5, 7, 8})
    void stampedCatalogsMigrateAndRemainQueryable(int stamped) throws Exception {
        String url = "jdbc:sqlite:file:hist-" + stamped + "-" + System.nanoTime() + "?mode=memory&cache=shared";
        try (Connection seed = DriverManager.getConnection(url)) {
            try (Statement st = seed.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (" + stamped + ", 0)");
                st.execute("CREATE TABLE vg_users (id INTEGER PRIMARY KEY, uuid CHAR(36) NULL, name VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, UNIQUE(uuid), UNIQUE(name))");
                st.execute("CREATE TABLE vg_worlds (id INTEGER PRIMARY KEY, world_key VARCHAR(96) NOT NULL UNIQUE)");
                st.execute(actionsDdlFor(stamped));
                st.execute("INSERT INTO vg_users(id, uuid, name, first_seen, last_seen) VALUES (1, NULL, 'null', 1, 1)");
                st.execute("INSERT INTO vg_worlds(id, world_key) VALUES (1, 'minecraft:overworld')");
                st.execute("INSERT INTO vg_actions(id, ts, type, user_id, world_id, x, y, z, target, meta, amount, rolled_back, source_tag) "
                        + "VALUES (11, 200, 1, 1, 1, 2, 64, 2, '" + "x".repeat(200) + "', NULL, 1, 0, NULL)");
            }

            SqliteDao dao = new SqliteDao(url);
            try {
                dao.init();
                try (Connection c = DriverManager.getConnection(url);
                     Statement st = c.createStatement();
                     var rs = st.executeQuery("SELECT MAX(version) FROM vg_schema_version")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(Schema.CURRENT_VERSION);
                }
                List<Action> rows = dao.query(QueryFilter.empty(), 0, 10);
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0).id()).isEqualTo(11L);
                assertThat(rows.get(0).targetId()).isEqualTo("x".repeat(200));
                assertThat(rows.get(0).actorName()).isEqualTo("null");
                assertThat(dao.count(QueryFilter.empty())).isEqualTo(1L);
                assertThat(dao.insertBatch(List.of(new Action(
                        -1L, 300L, ActionType.BLOCK_PLACE, UUID.randomUUID(), "later",
                        "minecraft:overworld", 3, 64, 3, "minecraft:stone", null, 1, false, null)))).isEqualTo(1);
                dao.openRollbackBatch(UUID.randomUUID(), 0, "{}", List.of(11L));
                assertThat(dao.findByPairIds(Set.of(1L))).isNotNull();
            } finally {
                dao.close();
            }
        }
    }

    @Test
    void interruptionBetweenAlterAndStampIsRerunSafe() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE vg_schema_version (version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL)");
                st.execute("INSERT INTO vg_schema_version(version, applied_at) VALUES (2, 0)");
                st.execute(v2ActionsDdl());
            }
            new network.vonix.guardian.core.storage.migration.V3WidenActionTarget()
                    .apply(c, Schema.Dialect.SQLITE);
            // Version table still says 2 — crash window.
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT MAX(version) FROM vg_schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
            MigrationRunner.defaults().migrateToCurrent(c, Schema.Dialect.SQLITE);
            try (Statement st = c.createStatement();
                 var rs = st.executeQuery("SELECT MAX(version) FROM vg_schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(Schema.CURRENT_VERSION);
            }
        }
    }

    private static void assertUsableCurrentSchema(Connection c, long expectedId, String expectedTarget)
            throws Exception {
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT MAX(version) FROM vg_schema_version")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(Schema.CURRENT_VERSION);
        }
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("PRAGMA table_info(vg_actions)")) {
            Set<String> cols = new java.util.HashSet<>();
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
            assertThat(cols).contains("sign_side", "old_block_state", "pair_id", "inventory_slot");
        }
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT id, target FROM vg_actions WHERE id = " + expectedId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(expectedId);
            assertThat(rs.getString(2)).isEqualTo(expectedTarget);
        }
        try (Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('vg_repair_required','vg_sink_outbox')")) {
            Set<String> tables = new java.util.HashSet<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            assertThat(tables).contains("vg_repair_required", "vg_sink_outbox");
        }
    }

    private static String v2ActionsDdl() {
        return "CREATE TABLE vg_actions ("
                + "id INTEGER PRIMARY KEY, ts BIGINT NOT NULL, type SMALLINT NOT NULL, "
                + "user_id INTEGER NOT NULL, world_id INTEGER NOT NULL, "
                + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                + "target VARCHAR(192) NOT NULL, meta TEXT NULL, amount INTEGER NOT NULL DEFAULT 1, "
                + "rolled_back TINYINT NOT NULL DEFAULT 0, source_tag VARCHAR(64) NULL)";
    }

    private static String actionsDdlFor(int stamped) {
        StringBuilder sb = new StringBuilder(v2ActionsDdl());
        sb.setLength(sb.length() - 1);
        if (stamped >= 4) {
            sb.append(", sign_side VARCHAR(8) NULL, sign_dye_color VARCHAR(16) NULL, sign_waxed BOOLEAN NULL");
        }
        if (stamped >= 5) {
            sb.append(", old_block_state TEXT NULL, new_block_state TEXT NULL, "
                    + "block_entity_nbt BLOB NULL, item_nbt BLOB NULL, entity_nbt BLOB NULL");
        }
        if (stamped >= 6) {
            sb.append(", pair_id BIGINT NULL");
        }
        if (stamped >= 7) {
            sb.append(", inventory_slot INTEGER NULL");
        }
        sb.append(")");
        return sb.toString();
    }
}
