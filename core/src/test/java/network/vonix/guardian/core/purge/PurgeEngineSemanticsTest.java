package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurgeEngineSemanticsTest {

    private static final UUID USER = UUID.nameUUIDFromBytes("purge-user".getBytes());
    private static final String WORLD = "minecraft:overworld";
    private static final long DAY_MS = 86_400_000L;

    @Test
    void manual_purge_deletes_rows_older_than_time_bound_not_newer(@TempDir Path tmp) throws Exception {
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("guardian.db"))) {
            dao.init();
            long now = System.currentTimeMillis();
            long fortyDaysAgo = now - 40L * DAY_MS;
            long twentyDaysAgo = now - 20L * DAY_MS;
            long oneDayAgo = now - DAY_MS;

            dao.insertBatch(List.of(
                action(fortyDaysAgo, "older-than-retention"),
                action(twentyDaysAgo, "inside-retention"),
                action(oneDayAgo, "newest")
            ));

            QueryFilter purgeThirtyDays = QueryFilter.builder()
                .sinceMillis(now - 30L * DAY_MS)
                .build();

            PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(purgeThirtyDays, 30L * 86_400L);

            assertThat(result.deletedCount()).isEqualTo(1L);
            assertThat(dao.query(QueryFilter.empty(), 0, 10))
                .extracting(Action::sourceTag)
                .containsExactly("newest", "inside-retention");
        }
    }

    @Test
    void ranged_purge_deletes_only_rows_inside_the_window(@TempDir Path tmp) throws Exception {
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("guardian.db"))) {
            dao.init();
            long now = System.currentTimeMillis();
            dao.insertBatch(List.of(
                action(now - 60L * DAY_MS, "older-than-window", "minecraft:overworld"),
                action(now - 45L * DAY_MS, "inside-window", "minecraft:overworld"),
                action(now - 10L * DAY_MS, "newer-than-window", "minecraft:the_nether")
            ));

            QueryFilter window = QueryFilter.builder()
                .sinceMillis(now - 50L * DAY_MS)
                .untilMillis(now - 40L * DAY_MS)
                .build();

            PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(window, 30L * 86_400L);

            assertThat(result.deletedCount()).isEqualTo(1L);
            assertThat(dao.query(QueryFilter.empty(), 0, 10))
                .extracting(Action::sourceTag)
                .containsExactlyInAnyOrder("older-than-window", "newer-than-window");
        }
    }

    @Test
    void ranged_purge_refuses_when_the_newer_edge_is_inside_the_safety_floor(@TempDir Path tmp) throws Exception {
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("guardian.db"))) {
            dao.init();
            long now = System.currentTimeMillis();
            PurgeEngine engine = new PurgeEngine(dao);
            QueryFilter mostlyOldButReachesYesterday = QueryFilter.builder()
                .sinceMillis(now - 90L * DAY_MS)
                .untilMillis(now - DAY_MS)
                .build();
            assertThatThrownBy(() -> engine.purge(mostlyOldButReachesYesterday, 30L * 86_400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time filter");
        }
    }

    @Test
    void purge_without_a_world_removes_every_world_and_a_named_world_stays_narrow(@TempDir Path tmp) throws Exception {
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("guardian.db"))) {
            dao.init();
            long now = System.currentTimeMillis();
            long old = now - 40L * DAY_MS;
            dao.insertBatch(List.of(
                action(old, "overworld-row", "minecraft:overworld"),
                action(old, "nether-row", "minecraft:the_nether")
            ));

            QueryFilter named = QueryFilter.builder()
                .sinceMillis(now - 30L * DAY_MS)
                .worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false))
                .build();
            PurgeEngine.PurgeResult netherOnly = new PurgeEngine(dao).purge(named, 30L * 86_400L);
            assertThat(netherOnly.deletedCount()).isEqualTo(1L);
            assertThat(dao.query(QueryFilter.empty(), 0, 10))
                .extracting(Action::sourceTag)
                .containsExactly("overworld-row");

            QueryFilter bare = QueryFilter.builder()
                .sinceMillis(now - 30L * DAY_MS)
                .build();
            assertThat(new PurgeEngine(dao).purge(bare, 30L * 86_400L).deletedCount()).isEqualTo(1L);
            assertThat(dao.query(QueryFilter.empty(), 0, 10)).isEmpty();
        }
    }

    @Test
    void manual_purge_rejects_missing_or_too_recent_time_bound(@TempDir Path tmp) throws Exception {
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite:" + tmp.resolve("guardian.db"))) {
            dao.init();
            PurgeEngine engine = new PurgeEngine(dao);
            long now = System.currentTimeMillis();

            assertThatThrownBy(() -> engine.purge(QueryFilter.empty(), 30L * 86_400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time filter");

            QueryFilter tooRecent = QueryFilter.builder()
                .sinceMillis(now - DAY_MS)
                .build();
            assertThatThrownBy(() -> engine.purge(tooRecent, 30L * 86_400L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time filter");
        }
    }

    private static Action action(long timestamp, String sourceTag) {
        return action(timestamp, sourceTag, WORLD);
    }

    private static Action action(long timestamp, String sourceTag, String world) {
        return new Action(-1L, timestamp, ActionType.BLOCK_PLACE,
            USER, "PurgeUser", world, 0, 64, 0,
            "minecraft:stone", null, 1, false, sourceTag);
    }
}
