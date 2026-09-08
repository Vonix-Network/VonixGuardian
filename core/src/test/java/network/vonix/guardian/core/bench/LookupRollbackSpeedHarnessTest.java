package network.vonix.guardian.core.bench;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackEngine;
import network.vonix.guardian.core.rollback.WorldMutator;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic in-process harness for lookup/count/rollback latency on a
 * disposable SQLite catalog. Numbers are wall-clock on the test host; they
 * are not a 2.1.0 matched baseline.
 */
class LookupRollbackSpeedHarnessTest {

    private SqliteDao dao;

    @AfterEach
    void tearDown() {
        if (dao != null) dao.close();
    }

    @Test
    void recordsLookupCountAndRollbackLatencies() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        int n = 5_000;
        List<Action> batch = new ArrayList<>(n);
        long now = 1_700_000_000_000L;
        UUID actor = UUID.nameUUIDFromBytes("bench".getBytes());
        for (int i = 0; i < n; i++) {
            batch.add(new Action(
                    -1L, now - i, ActionType.BLOCK_PLACE, actor, "bench",
                    "minecraft:overworld", i % 64, 64, i / 64,
                    "minecraft:stone", null, 1, false, null));
        }
        dao.insertBatch(batch);

        QueryFilter hour = QueryFilter.builder()
                .sinceMillis(now - 3_600_000L)
                .radius(10)
                .center(0, 64, 0)
                .build();

        long t0 = System.nanoTime();
        List<Action> page1 = dao.query(hour, 0, 8);
        long p1 = System.nanoTime() - t0;
        t0 = System.nanoTime();
        List<Action> page2 = dao.query(hour, 8, 8);
        long p2 = System.nanoTime() - t0;
        t0 = System.nanoTime();
        long count = dao.count(hour);
        long countNs = System.nanoTime() - t0;

        WorldMutator mutator = new WorldMutator() {
            @Override
            public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
                return true;
            }
        };
        RollbackEngine engine = new RollbackEngine(dao, mutator, Runnable::run);
        QueryFilter rb = QueryFilter.builder()
                .sinceMillis(now - 3_600_000L)
                .radius(10)
                .center(0, 64, 0)
                .build();
        t0 = System.nanoTime();
        var result = engine.rollback(rb, false);
        long rbNs = System.nanoTime() - t0;

        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.printf(java.util.Locale.ROOT,
                "VG-2.1.1-harness rows=%d page1Ns=%d page2Ns=%d countNs=%d count=%d rollbackNs=%d applied=%d heapUsed=%d%n",
                n, p1, p2, countNs, count, rbNs, result.appliedCount(), heap);

        assertThat(page1).isNotEmpty();
        assertThat(page2).isNotEmpty();
        assertThat(count).isGreaterThan(0);
        assertThat(result.appliedCount()).isGreaterThan(0);
        assertThat(p1).isLessThan(5_000_000_000L);
        assertThat(rbNs).isLessThan(30_000_000_000L);
    }
}
