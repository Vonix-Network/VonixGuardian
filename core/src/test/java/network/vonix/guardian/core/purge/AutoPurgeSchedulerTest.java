package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3-B4: chunked auto-purge integration test.
 *
 * <p>Seeds 100 rows with a mix of timestamps older / newer than the 180-day
 * retention horizon and asserts that {@link AutoPurgeScheduler#runNow()}
 * leaves only recent rows in place, records the deleted total, and increments
 * {@link AutoPurgeScheduler#getRowsPurgedSinceRestart()}.
 */
class AutoPurgeSchedulerTest {

    private static final long RETENTION_SECONDS = 180L * 86_400L; // 180d

    private SqliteDao dao;
    private PurgeEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        engine = new PurgeEngine(dao);
    }

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    @Test
    void runNow_deletes_only_rows_older_than_retention() throws Exception {
        long now = 1_800_000_000_000L; // fixed reference
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("UTC"));

        // Seed 100 rows: 60 stale (>180d ago), 40 recent (<180d ago).
        UUID actor = UUID.nameUUIDFromBytes("purge-actor".getBytes());
        String actorName = "PurgeActor";
        String world = "minecraft:overworld";
        List<Action> batch = new ArrayList<>(100);
        long staleBase = now - 365L * 86_400L * 1000L;    // ~1yr ago
        long recentBase = now - 30L * 86_400L * 1000L;    // 30d ago
        for (int i = 0; i < 60; i++) {
            batch.add(new Action(-1L,
                    staleBase + i * 1000L,
                    ActionType.BLOCK_BREAK,
                    actor, actorName, world,
                    i, 64, i,
                    "minecraft:stone", null, 1, false, null));
        }
        for (int i = 0; i < 40; i++) {
            batch.add(new Action(-1L,
                    recentBase + i * 1000L,
                    ActionType.BLOCK_PLACE,
                    actor, actorName, world,
                    i, 64, i,
                    "minecraft:dirt", null, 1, false, null));
        }
        int inserted = dao.insertBatch(batch);
        assertThat(inserted).isEqualTo(100);
        assertThat(dao.count(QueryFilter.empty())).isEqualTo(100L);

        // Build a Purge config with autoPurgeSeconds=180d, "03:30", and craft a scheduler
        // driven by our fixed clock so we don't depend on wall time.
        GuardianConfig cfg = withPurge(GuardianConfig.defaults(),
                new GuardianConfig.Purge(86_400L, 2_592_000L, RETENTION_SECONDS, "03:30"));
        AutoPurgeScheduler sched =
                AutoPurgeScheduler.create(cfg, engine, dao, ZoneId.of("UTC"), fixed);

        assertThat(sched.isEnabled()).isTrue();

        long deleted = sched.runNow();

        assertThat(deleted).isEqualTo(60L);
        assertThat(dao.count(QueryFilter.empty())).isEqualTo(40L);
        assertThat(sched.getRowsPurgedSinceRestart()).isEqualTo(60L);
        assertThat(sched.getLastRunDeleted()).contains(60L);
    }

    @Test
    void nextRunOf_computes_tomorrow_when_time_already_passed() {
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 1, 10, 0, 0, 0, utc);
        ZonedDateTime next = AutoPurgeScheduler.nextRunOf(
                java.time.LocalTime.of(3, 30), utc, now);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 7, 2, 3, 30, 0, 0, utc));
    }

    @Test
    void nextRunOf_computes_today_when_time_still_future() {
        ZoneId utc = ZoneId.of("UTC");
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 1, 1, 0, 0, 0, utc);
        ZonedDateTime next = AutoPurgeScheduler.nextRunOf(
                java.time.LocalTime.of(3, 30), utc, now);
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 7, 1, 3, 30, 0, 0, utc));
    }

    @Test
    void disabled_when_autoPurgeSeconds_is_zero() throws Exception {
        GuardianConfig cfg = GuardianConfig.defaults(); // autoPurgeSeconds=0
        AutoPurgeScheduler sched = AutoPurgeScheduler.create(cfg, engine, dao);
        assertThat(sched.isEnabled()).isFalse();
        assertThat(sched.runNow()).isEqualTo(0L);
    }

    @Test
    void runNow_skips_when_mutex_is_held() throws Exception {
        GuardianConfig cfg = withPurge(GuardianConfig.defaults(),
                new GuardianConfig.Purge(86_400L, 2_592_000L, RETENTION_SECONDS, "03:30"));
        AutoPurgeScheduler sched = AutoPurgeScheduler.create(cfg, engine, dao);
        engine.mutex().lock();
        try {
            assertThat(sched.runNow()).isEqualTo(0L);
            assertThat(sched.getRowsPurgedSinceRestart()).isEqualTo(0L);
        } finally {
            engine.mutex().unlock();
        }
    }

    // ------------------------------------------------------------- helpers

    private static GuardianConfig withPurge(GuardianConfig base, GuardianConfig.Purge p) {
        return new GuardianConfig(
                base.database(), base.queue(), base.logFile(), base.actions(),
                base.permissions(), base.lookup(), base.privacy(), p, base.theme());
    }
}
