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

class SqliteDaoIntegrationTest {

    private SqliteDao dao;
    private final List<UUID> users = new ArrayList<>();
    private final List<String> userNames = new ArrayList<>();
    private final List<String> worlds = List.of(
        "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");

    @BeforeEach
    void setUp() throws Exception {
        // Each test gets its own in-memory DB
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        users.clear();
        userNames.clear();
        for (int i = 0; i < 5; i++) {
            users.add(UUID.nameUUIDFromBytes(("user-" + i).getBytes()));
            userNames.add("User" + i);
        }
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    @Test
    void inserts_and_queries_1000_actions() throws Exception {
        long t0 = 1_700_000_000_000L;
        List<Action> batch = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            int u = i % 5;
            int w = i % 3;
            ActionType type = (i % 2 == 0) ? ActionType.BLOCK_PLACE : ActionType.BLOCK_BREAK;
            String target = (i % 4 == 0) ? "minecraft:stone" : "minecraft:dirt";
            batch.add(new Action(
                -1L,
                t0 + i * 1000L,
                type,
                users.get(u),
                userNames.get(u),
                worlds.get(w),
                i % 100, 64, (i / 100) % 100,
                target,
                null,
                1,
                false,
                null
            ));
        }
        int inserted = dao.insertBatch(batch);
        assertThat(inserted).isEqualTo(1000);

        // Total count
        long total = dao.count(QueryFilter.empty());
        assertThat(total).isEqualTo(1000);

        // Query by one user
        QueryFilter byUser = QueryFilter.builder()
            .addUser(new QueryFilter.UserSel(users.get(0), userNames.get(0), false))
            .build();
        long userCount = dao.count(byUser);
        assertThat(userCount).isEqualTo(200);
        List<Action> page = dao.query(byUser, 0, 50);
        assertThat(page).hasSize(50);
        assertThat(page).allMatch(a -> a.actorUuid() != null && a.actorUuid().equals(users.get(0)));

        // Pagination
        List<Action> p2 = dao.query(byUser, 50, 50);
        assertThat(p2).hasSize(50);
        assertThat(page.get(0).id()).isNotEqualTo(p2.get(0).id());

        // Query by world
        QueryFilter byWorld = QueryFilter.builder()
            .worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false))
            .build();
        long nether = dao.count(byWorld);
        // worlds cycle 0,1,2 → world index 1 used by i%3==1 → 333 or 334 of 1000.
        assertThat(nether).isBetween(333L, 334L);

        // Query by action type
        QueryFilter byBreak = QueryFilter.builder()
            .addAction(new QueryFilter.ActionSelect(ActionType.BLOCK_BREAK, QueryFilter.ActionSelect.Sign.ANY))
            .build();
        assertThat(dao.count(byBreak)).isEqualTo(500);

        // Time window
        QueryFilter byTime = QueryFilter.builder()
            .sinceMillis(t0)
            .untilMillis(t0 + 99 * 1000L)
            .build();
        assertThat(dao.count(byTime)).isEqualTo(100);

        // Include filter
        QueryFilter stoneOnly = QueryFilter.builder().addInclude("minecraft:stone").build();
        assertThat(dao.count(stoneOnly)).isEqualTo(250);

        // Exclude filter
        QueryFilter notDirt = QueryFilter.builder().addExclude("minecraft:dirt").build();
        assertThat(dao.count(notDirt)).isEqualTo(250);

        // Radius filter — center 0,64,0 with r=5 includes x∈[-5,5], z∈[-5,5], y∈[59,69]
        QueryFilter byRadius = QueryFilter.builder()
            .radius(5)
            .center(0, 64, 0)
            .build();
        long radiusCount = dao.count(byRadius);
        // x=i%100 ∈ {0..5}, z=(i/100)%100 ∈ {0..5} → 6 x-values × 6 z-values × 2 (types alternation irrelevant)
        // but i must satisfy both: i%100 ≤ 5 AND (i/100)%100 ≤ 5
        // → i in {0..5, 100..105, 200..205, ..., 900..905} = 10 * 6 = 60
        assertThat(radiusCount).isEqualTo(36);
    }

    @Test
    void mark_rolled_back_updates_rows() throws Exception {
        List<Action> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(new Action(-1L, 1_700_000_000_000L + i, ActionType.BLOCK_BREAK,
                users.get(0), userNames.get(0), worlds.get(0),
                i, 64, 0, "minecraft:stone", null, 1, false, null));
        }
        dao.insertBatch(batch);
        List<Action> rows = dao.query(QueryFilter.empty(), 0, 100);
        assertThat(rows).hasSize(10);
        List<Long> ids = rows.stream().map(Action::id).toList().subList(0, 5);
        int updated = dao.markRolledBack(ids, true);
        assertThat(updated).isEqualTo(5);
        List<Action> after = dao.query(QueryFilter.empty(), 0, 100);
        long flagged = after.stream().filter(Action::rolledBack).count();
        assertThat(flagged).isEqualTo(5);
    }

    @Test
    void purge_deletes_filtered_rows() throws Exception {
        List<Action> batch = new ArrayList<>();
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 20; i++) {
            batch.add(new Action(-1L, t0 + i, ActionType.BLOCK_PLACE,
                users.get(0), userNames.get(0), worlds.get(0),
                i, 64, 0, "minecraft:stone", null, 1, false, null));
        }
        dao.insertBatch(batch);
        QueryFilter half = QueryFilter.builder().untilMillis(t0 + 9).build();
        long purged = dao.purge(half);
        assertThat(purged).isEqualTo(10);
        assertThat(dao.count(QueryFilter.empty())).isEqualTo(10);
    }

    @Test
    void resolve_user_and_world_are_cached_and_unique() throws Exception {
        UUID u = users.get(0);
        int id1 = dao.resolveUser(u, "Alice");
        int id2 = dao.resolveUser(u, "Alice");
        assertThat(id1).isEqualTo(id2);
        int w1 = dao.resolveWorld("minecraft:overworld");
        int w2 = dao.resolveWorld("minecraft:overworld");
        assertThat(w1).isEqualTo(w2);
        int wOther = dao.resolveWorld("minecraft:the_nether");
        assertThat(wOther).isNotEqualTo(w1);
    }

    @Test
    void health_check_returns_true_when_open() {
        assertThat(dao.isHealthy()).isTrue();
    }

    @Test
    void init_is_idempotent() throws Exception {
        dao.init();
        dao.init();
        assertThat(dao.isHealthy()).isTrue();
    }

    @Test
    void optimize_runs_vacuum_and_returns_completed_result() throws Exception {
        // Populate + purge to create free pages, then VACUUM should reclaim them.
        long t0 = 1_700_000_000_000L;
        List<Action> batch = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            batch.add(new Action(-1L, t0 + i, ActionType.BLOCK_PLACE,
                users.get(0), userNames.get(0), worlds.get(0),
                i, 64, 0, "minecraft:stone", null, 1, false, null));
        }
        dao.insertBatch(batch);
        dao.purge(QueryFilter.builder().untilMillis(t0 + 100).build());

        var result = dao.optimize(60_000L);

        assertThat(result).isNotNull();
        assertThat(result.completed()).isTrue();
        assertThat(result.durationMillis()).isGreaterThanOrEqualTo(0L);
        // SQLite reports page_count * page_size, so bytesFreed should be a real
        // number (>= -1 always; typically >= 0 on a purged DB).
        assertThat(result.bytesFreed()).isGreaterThanOrEqualTo(-1L);
        // DB still functional after VACUUM.
        assertThat(dao.isHealthy()).isTrue();
        assertThat(dao.count(QueryFilter.empty())).isGreaterThan(0L);
    }
}
