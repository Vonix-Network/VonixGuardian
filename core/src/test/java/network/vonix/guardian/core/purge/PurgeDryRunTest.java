package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.command.PurgeReport;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code #preview} and {@code #count} must not delete. This test uses a
 * recording store, not a database: the old engine called {@code purge()}
 * unconditionally, so {@code purgeCalls} was 1 and {@code deletedCount}
 * was the store's delete return value.
 */
class PurgeDryRunTest {

    private static final long DAY_MS = 86_400_000L;

    @Test
    void countFlagCountsAndDoesNotDelete() throws Exception {
        RecordingDao dao = new RecordingDao();
        PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(aged(false, true), 30L * 86_400L);

        assertThat(dao.purgeCalls).isZero();
        assertThat(dao.optimizeCalls).isZero();
        assertThat(dao.queryCalls).isZero();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.mode()).isEqualTo(PurgeEngine.PurgeResult.Mode.COUNT);
        assertThat(result.deletedCount()).isZero();
        assertThat(result.matchedCount()).isEqualTo(4L);
        assertThat(result.sample()).isEmpty();
        assertThat(PurgeReport.lines(result))
            .containsExactly("[VonixGuardian] Purge count: 4 rows match in all worlds. Nothing was deleted.");
    }

    @Test
    void previewFlagSamplesAndDoesNotDelete() throws Exception {
        RecordingDao dao = new RecordingDao();
        PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(aged(true, false), 30L * 86_400L);

        assertThat(dao.purgeCalls).isZero();
        assertThat(dao.optimizeCalls).isZero();
        assertThat(dao.queryCalls).isEqualTo(1);
        assertThat(result.mode()).isEqualTo(PurgeEngine.PurgeResult.Mode.PREVIEW);
        assertThat(result.deletedCount()).isZero();
        assertThat(result.matchedCount()).isEqualTo(4L);
        assertThat(result.sample()).extracting(Action::targetId).containsExactly("minecraft:stone");
        List<String> lines = PurgeReport.lines(result);
        assertThat(lines.get(0)).contains("Purge preview: 4").contains("Nothing was deleted");
        assertThat(lines).anyMatch(line -> line.contains("minecraft:overworld") && line.contains("minecraft:stone"));
        assertThat(String.join("\n", lines)).doesNotContain("removed");
    }

    @Test
    void previewWinsWhenBothFlagsAreSet() throws Exception {
        RecordingDao dao = new RecordingDao();
        PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(aged(true, true), 30L * 86_400L);

        assertThat(dao.purgeCalls).isZero();
        assertThat(result.mode()).isEqualTo(PurgeEngine.PurgeResult.Mode.PREVIEW);
        assertThat(result.deletedCount()).isZero();
    }

    @Test
    void realPurgeStillDeletes() throws Exception {
        RecordingDao dao = new RecordingDao();
        PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(aged(false, false), 30L * 86_400L);

        assertThat(dao.purgeCalls).isEqualTo(1);
        assertThat(result.mode()).isEqualTo(PurgeEngine.PurgeResult.Mode.DELETE);
        assertThat(result.deletedCount()).isEqualTo(4L);
        assertThat(result.dryRun()).isFalse();
        assertThat(PurgeReport.lines(result).get(0)).contains("Purge removed 4 rows");
    }

    private static QueryFilter aged(boolean preview, boolean count) {
        long now = System.currentTimeMillis();
        return QueryFilter.builder()
            .sinceMillis(now - 40L * DAY_MS)
            .preview(preview)
            .countOnly(count)
            .optimize(true)
            .build();
    }

    /** Store that records deletes. Unused methods fail the test if a dry run touches them. */
    private static final class RecordingDao implements GuardianDao {
        final AtomicInteger purgeCallsBox = new AtomicInteger();
        int purgeCalls;
        int optimizeCalls;
        int queryCalls;
        final Action sample = new Action(7L, 1L, ActionType.BLOCK_BREAK,
            UUID.nameUUIDFromBytes("dry".getBytes()), "Steve", "minecraft:overworld",
            3, 64, 9, "minecraft:stone", null, 1, false, null);

        @Override public void init() { throw new UnsupportedOperationException(); }
        @Override public int insertBatch(List<Action> batch) { throw new UnsupportedOperationException(); }
        @Override public List<Action> query(QueryFilter filter, int offset, int limit) {
            queryCalls++;
            return List.of(sample);
        }
        @Override public long count(QueryFilter filter) { return 4L; }
        @Override public int markRolledBack(List<Long> ids, boolean rolledBack) { throw new UnsupportedOperationException(); }
        @Override public long purge(QueryFilter filter) {
            purgeCalls = purgeCallsBox.incrementAndGet();
            return 4L;
        }
        @Override public OptimizeResult optimize(long maxRuntimeMillis) {
            optimizeCalls++;
            return new OptimizeResult(0, 0, true);
        }
        @Override public long purgeOlderThan(long cutoffMillis, int chunkLimit) { throw new UnsupportedOperationException(); }
        @Override public int resolveUser(UUID uuid, String name) { throw new UnsupportedOperationException(); }
        @Override public int resolveWorld(String key) { throw new UnsupportedOperationException(); }
        @Override public long openRollbackBatch(UUID actorUuid, int mode, String filterJson, List<Long> actionIds) { throw new UnsupportedOperationException(); }
        @Override public int closeRollbackBatch(long batchId) { throw new UnsupportedOperationException(); }
        @Override public List<Long> findIncompleteBatchActionIds() { throw new UnsupportedOperationException(); }
        @Override public boolean hasActionsInWindow(UUID user, String worldId, int x, int y, int z, ActionType[] types, long withinMillis) { throw new UnsupportedOperationException(); }
        @Override public boolean isHealthy() { return true; }
        @Override public void close() {}
    }
}
