package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyset pagination must return the same rows as OFFSET for a matched
 * SQLite workload, including equal-timestamp ties broken by id.
 */
class KeysetPaginationTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final long T0 = 1_700_000_000_000L;

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
    void keysetPagesMatchOffsetPagesIncludingTimestampTies() throws Exception {
        List<Action> batch = new ArrayList<>(80);
        for (int i = 0; i < 80; i++) {
            long ts = T0 + (i / 4) * 1000L;
            batch.add(new Action(
                    -1L, ts, ActionType.BLOCK_PLACE, ACTOR, "user",
                    "minecraft:overworld", i, 64, 0, "minecraft:stone",
                    null, 1, false, null));
        }
        assertThat(dao.insertBatch(batch)).isEqualTo(80);

        QueryFilter filter = QueryFilter.empty();
        List<Action> offsetAll = new ArrayList<>();
        List<Action> keysetAll = new ArrayList<>();
        Action cursor = null;
        int offset = 0;
        int pageSize = 11;
        while (true) {
            GuardianDao.QueryPage offsetPage = dao.queryPage(filter, offset, pageSize);
            if (offsetPage.rows().isEmpty()) {
                break;
            }
            offsetAll.addAll(offsetPage.rows());
            GuardianDao.QueryPage keysetPage;
            if (cursor == null) {
                keysetPage = dao.queryPage(filter, 0, pageSize);
            } else {
                keysetPage = dao.queryPageAfter(filter, cursor.timestamp(), cursor.id(), pageSize);
            }
            assertThat(keysetPage.rows())
                    .extracting(Action::id)
                    .containsExactlyElementsOf(offsetPage.rows().stream().map(Action::id).toList());
            keysetAll.addAll(keysetPage.rows());
            cursor = keysetPage.rows().get(keysetPage.rows().size() - 1);
            offset += offsetPage.rows().size();
            if (offsetPage.rows().size() < pageSize) {
                break;
            }
        }

        assertThat(keysetAll).hasSize(80);
        assertThat(offsetAll).extracting(Action::id)
                .containsExactlyElementsOf(keysetAll.stream().map(Action::id).toList());
        GuardianDao.QueryPage afterLast = dao.queryPageAfter(
                filter, cursor.timestamp(), cursor.id(), pageSize);
        assertThat(afterLast.rows()).isEmpty();
    }

    @Test
    void displayKeysetOmitsNbtButKeepsOrder() throws Exception {
        byte[] nbt = new byte[] {1, 2, 3};
        Action stored = new Action(
                -1L, T0, ActionType.BLOCK_BREAK, ACTOR, "user",
                "minecraft:overworld", 1, 64, 1, "minecraft:chest",
                null, 1, false, null, null, null, null,
                "facing=north", null, nbt, null, null);
        assertThat(dao.insertBatch(List.of(stored))).isEqualTo(1);

        List<Action> full = dao.query(QueryFilter.empty(), 0, 1);
        assertThat(full.get(0).blockEntityNbt()).containsExactly(nbt);

        GuardianDao.QueryPage display = dao.queryPageForDisplayAfter(
                QueryFilter.empty(), T0 + 1, Long.MAX_VALUE, 1);
        assertThat(display.rows()).hasSize(1);
        assertThat(display.rows().get(0).id()).isEqualTo(full.get(0).id());
        assertThat(display.rows().get(0).blockEntityNbt()).isNull();
        assertThat(display.rows().get(0).oldBlockState()).isNull();
    }
}
