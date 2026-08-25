package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-page rollback planning against a real SQLite DAO must consume every
 * matching row exactly once when later pages use keyset seeks.
 */
class RollbackKeysetScanTest {

    private static final UUID ACTOR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long T0 = 1_720_000_000_000L;

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
    void multiPageRollbackPlansEveryRowOnceViaKeyset() throws Exception {
        int n = 35;
        List<Action> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(new Action(
                    -1L, T0 + i, ActionType.BLOCK_PLACE, ACTOR, "user",
                    "minecraft:overworld", i, 64, 0, "minecraft:stone",
                    null, 1, false, null));
        }
        assertThat(dao.insertBatch(batch)).isEqualTo(n);

        AtomicInteger afterCalls = new AtomicInteger();
        GuardianDao counting = new CountingAfterDao(dao, afterCalls);
        RollbackEngine engine = new RollbackEngine(counting, new WorldMutator() {}, Runnable::run);
        QueryFilter filter = QueryFilter.builder()
                .sinceMillis(T0 - 1)
                .build();
        RollbackOptions options = new RollbackOptions(10, 1_000, 1_000, () -> false, ignored -> { });

        RollbackResult result = engine.rollback(filter, true, options);

        assertThat(result.preview()).isTrue();
        assertThat(result.affectedIds()).hasSize(n);
        assertThat(afterCalls.get()).isGreaterThanOrEqualTo(2);
    }

    private static final class CountingAfterDao implements GuardianDao {
        private final SqliteDao inner;
        private final AtomicInteger afterCalls;

        private CountingAfterDao(SqliteDao inner, AtomicInteger afterCalls) {
            this.inner = inner;
            this.afterCalls = afterCalls;
        }

        @Override public void init() throws Exception { inner.init(); }
        @Override public int insertBatch(List<Action> batch) throws Exception { return inner.insertBatch(batch); }
        @Override public List<Action> query(QueryFilter filter, int offset, int limit) throws Exception {
            return inner.query(filter, offset, limit);
        }
        @Override public QueryPage queryPage(QueryFilter filter, int offset, int limit) throws Exception {
            return inner.queryPage(filter, offset, limit);
        }
        @Override public QueryPage queryPageAfter(QueryFilter filter, long afterTs, long afterId, int limit)
                throws Exception {
            afterCalls.incrementAndGet();
            return inner.queryPageAfter(filter, afterTs, afterId, limit);
        }
        @Override public long count(QueryFilter filter) throws Exception { return inner.count(filter); }
        @Override public int markRolledBack(List<Long> ids, boolean rolledBack) throws Exception {
            return inner.markRolledBack(ids, rolledBack);
        }
        @Override public long purge(QueryFilter filter) throws Exception { return inner.purge(filter); }
        @Override public OptimizeResult optimize(long maxRuntimeMillis) throws Exception {
            return inner.optimize(maxRuntimeMillis);
        }
        @Override public long purgeOlderThan(long cutoffMillis, int chunkLimit) throws Exception {
            return inner.purgeOlderThan(cutoffMillis, chunkLimit);
        }
        @Override public int resolveUser(UUID uuid, String name) throws Exception {
            return inner.resolveUser(uuid, name);
        }
        @Override public int resolveWorld(String key) throws Exception { return inner.resolveWorld(key); }
        @Override public long openRollbackBatch(UUID actorUuid, int mode, String filterJson, List<Long> actionIds)
                throws Exception {
            return inner.openRollbackBatch(actorUuid, mode, filterJson, actionIds);
        }
        @Override public int closeRollbackBatch(long batchId) throws Exception {
            return inner.closeRollbackBatch(batchId);
        }
        @Override public List<Long> findIncompleteBatchActionIds() throws Exception {
            return inner.findIncompleteBatchActionIds();
        }
        @Override public boolean hasActionsInWindow(UUID user, String worldId, int x, int y, int z,
                                                    ActionType[] types, long withinMillis) throws Exception {
            return inner.hasActionsInWindow(user, worldId, x, y, z, types, withinMillis);
        }
        @Override public boolean isHealthy() { return inner.isHealthy(); }
        @Override public void close() { inner.close(); }
    }
}
