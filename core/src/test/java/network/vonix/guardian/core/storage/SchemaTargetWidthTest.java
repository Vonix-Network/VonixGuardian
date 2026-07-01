package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A11 regression: {@code vg_actions.target} must survive a 512-char CHAT
 * submit round-trip without truncation.
 *
 * <p>Reproduces the Berk maintenance-window incident from 2026-07-01 08:05:54 UTC:
 * a {@code /tellraw} body ~500 chars long was pushed into a column previously
 * declared {@code VARCHAR(192) NOT NULL}, which triggered MySQL
 * {@code SQLDataException: Data truncation} at batch size 1 after the writer
 * degraded trying to isolate the poison row.
 *
 * <p>SQLite's type system uses affinity, so the v2 schema on SQLite was
 * <i>silently</i> already-permissive; this test is still valuable because it
 * exercises the round-trip through the DAO, {@code ActionBuilder}-shaped
 * inputs, and asserts the reader returns the full 512-character string byte-for-byte.
 */
class SchemaTargetWidthTest {

    private SqliteDao dao;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    @Test
    void chat_submit_of_512_chars_round_trips_without_truncation() throws Exception {
        String longChat = "x".repeat(512);
        assertThat(longChat).hasSize(512);

        UUID actor = UUID.nameUUIDFromBytes("WeedMeister".getBytes());
        Action chat = new Action(
            -1L,
            1_700_000_000_000L,
            ActionType.CHAT,
            actor,
            "WeedMeister",
            "minecraft:overworld",
            0, 64, 0,
            longChat,       // <-- the target column that used to be VARCHAR(192)
            null,
            1,
            false,
            null
        );

        int inserted = dao.insertBatch(List.of(chat));
        assertThat(inserted).isEqualTo(1);

        List<Action> rows = dao.query(QueryFilter.empty(), 0, 10);
        assertThat(rows).hasSize(1);
        Action back = rows.get(0);
        assertThat(back.type()).isEqualTo(ActionType.CHAT);
        assertThat(back.targetId())
            .as("target column must preserve all 512 chars — no VARCHAR(192) truncation")
            .hasSize(512)
            .isEqualTo(longChat);
    }

    @Test
    void schema_version_is_stamped_at_current_version_on_fresh_install() throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Schema.createTables(c, Schema.Dialect.SQLITE);
            try (var st = c.createStatement();
                 var rs = st.executeQuery("SELECT MAX(version) FROM vg_schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(Schema.CURRENT_VERSION);
            }
        }
    }
}
