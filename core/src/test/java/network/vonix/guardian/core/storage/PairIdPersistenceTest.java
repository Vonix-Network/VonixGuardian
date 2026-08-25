package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durable pairId: insert, query, sibling lookup, and file-backed reopen
 * (restart) all preserve the pairing token.
 */
class PairIdPersistenceTest {

    @Test
    void insert_query_and_reopen_preserve_pair_id(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("guardian.db");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000042");
        long pair = 99L;

        SqliteDao dao = new SqliteDao("jdbc:sqlite:" + db);
        dao.init();
        dao.insertBatch(List.of(
                new Action(-1L, 1_700_000_000_000L, ActionType.ENTITY_CHANGE_BLOCK,
                        actor, "#entity", "minecraft:overworld",
                        10, 64, 20, "minecraft:oak_log", "minecraft:air", 1, false, "#entity",
                        null, null, null, null, null, null, null, null, pair),
                new Action(-1L, 1_700_000_000_050L, ActionType.IGNITE,
                        actor, "#entity", "minecraft:overworld",
                        11, 64, 20, "minecraft:fire", null, 1, false, "entity:#entity",
                        null, null, null, null, null, null, null, null, pair),
                new Action(-1L, 1_700_000_000_100L, ActionType.BLOCK_PLACE,
                        actor, "Notch", "minecraft:overworld",
                        0, 70, 0, "minecraft:stone", null, 1, false, null)
        ));
        dao.close();

        dao = new SqliteDao("jdbc:sqlite:" + db);
        dao.init();
        List<Action> rows = dao.query(QueryFilter.empty(), 0, 10);
        assertThat(rows).hasSize(3);

        Action ignite = rows.stream().filter(a -> a.type() == ActionType.IGNITE).findFirst().orElseThrow();
        Action brk = rows.stream().filter(a -> a.type() == ActionType.ENTITY_CHANGE_BLOCK).findFirst().orElseThrow();
        Action place = rows.stream().filter(a -> a.type() == ActionType.BLOCK_PLACE).findFirst().orElseThrow();
        assertThat(ignite.pairId()).isEqualTo(pair);
        assertThat(brk.pairId()).isEqualTo(pair);
        assertThat(place.pairId()).isNull();

        List<Action> siblings = dao.findByPairIds(List.of(pair));
        assertThat(siblings).extracting(Action::type)
                .containsExactlyInAnyOrder(ActionType.ENTITY_CHANGE_BLOCK, ActionType.IGNITE);
        dao.close();
    }

    @Test
    void inventory_slot_round_trips_through_full_and_display_projections() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        Action action = new network.vonix.guardian.core.action.ActionBuilder()
                .type(ActionType.INVENTORY_DEPOSIT)
                .actorUuid(UUID.fromString("00000000-0000-0000-0000-000000000077"))
                .actorName("Player")
                .worldId("minecraft:overworld")
                .position(1, 64, 2)
                .targetId("minecraft:diamond")
                .amount(3)
                .itemNbt(new byte[] {1, 2, 3})
                .inventorySlot(7)
                .build();
        dao.insertBatch(List.of(action));

        Action full = dao.query(QueryFilter.empty(), 0, 2).get(0);
        assertThat(full.inventorySlot()).isEqualTo(7);
        assertThat(full.itemNbt()).containsExactly(1, 2, 3);

        Action display = dao.queryPageForDisplay(QueryFilter.empty(), 0, 2).rows().get(0);
        assertThat(display.inventorySlot()).isEqualTo(7);
        assertThat(display.itemNbt()).isNull();
        dao.close();
    }

    @Test
    void unpaired_null_and_zero_are_not_siblings() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        UUID actor = UUID.randomUUID();
        dao.insertBatch(List.of(
                new Action(-1L, 1L, ActionType.IGNITE, actor, "#fire", "minecraft:overworld",
                        0, 64, 0, "minecraft:fire", null, 1, false, null),
                new Action(-1L, 2L, ActionType.BURN, actor, "#fire", "minecraft:overworld",
                        1, 64, 0, "minecraft:oak_log", null, 1, false, null, null, null, null,
                        null, null, null, null, null, 0L)
        ));
        assertThat(dao.findByPairIds(java.util.Arrays.asList(0L, null))).isEmpty();
        dao.close();
    }
}
