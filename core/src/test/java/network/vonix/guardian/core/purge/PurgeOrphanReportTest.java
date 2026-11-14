package network.vonix.guardian.core.purge;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A purge must not leave the identity tables to grow forever, and it must never
 * silently imply it cleaned them when it did not.
 *
 * <h3>Where the delete lives</h3>
 * <p>{@code vg_users} and {@code vg_worlds} are populated on demand by
 * {@code resolveUserOn} / {@code resolveWorldOn} during insert. Neither table has
 * a foreign key into {@code vg_actions}, and before this work nothing in the tree
 * deleted from them — {@code git grep "DELETE FROM vg_users|vg_worlds" HEAD} was
 * empty — so every purge stranded one row per player and per world for good.
 *
 * <p>The delete belongs in the DAO, inside the purge transaction, because the DAO
 * is the only thing that can drop the {@code uuid -> user_id} /
 * {@code world -> world_id} caches that {@code resolveUser} / {@code resolveWorld}
 * consult <em>before</em> the table. Deleting a row whose id is still cached would
 * make the next insert write an id with no row, and {@code QueryCompiler}
 * inner-joins both tables, so those actions would be invisible to every lookup.
 *
 * <p>These tests therefore pin the <em>engine's</em> half: it asks the DAO what
 * residue remains, reports it truthfully, and never turns an unmeasurable or
 * non-zero residue into a clean-looking zero. A purge that reports nothing is
 * only allowed when it actually deleted nothing.
 */
class PurgeOrphanReportTest {

    private static final long DAY_MS = 86_400_000L;
    private static final long MIN_AGE = 30L * 86_400L;

    @Test
    void aPurgeThatLeavesOrphansSaysSo() {
        StubDao dao = new StubDao(7L);
        PurgeEngine.PurgeResult result = purge(dao);

        assertThat(result.deletedCount()).isEqualTo(4L);
        assertThat(result.orphans().scanned()).isTrue();
        assertThat(result.orphans().remaining()).isEqualTo(7L);
        assertThat(result.orphans().anyKept()).isTrue();
        assertThat(result.orphans().lines())
            .singleElement()
            .asString()
            .contains("7 unused player/world rows remain after this purge");
    }

    @Test
    void aCleanPurgeReportsNoResidueAndPrintsNothingExtra() {
        StubDao dao = new StubDao(0L);
        PurgeEngine.PurgeResult result = purge(dao);

        assertThat(result.orphans().scanned()).isTrue();
        assertThat(result.orphans().remaining()).isZero();
        assertThat(result.orphans().anyKept()).isFalse();
        assertThat(result.orphans().lines()).isEmpty();
        // Exactly one row count is stated, so the operator can reconcile it.
        assertThat(result.deletedCount()).isEqualTo(result.matchedCount()).isEqualTo(4L);
    }

    @Test
    void anUnmeasurableResidueIsNeverReportedAsZero() {
        StubDao dao = new StubDao(-1L);
        PurgeEngine.PurgeResult result = purge(dao);

        assertThat(result.orphans().remaining()).isEqualTo(PurgeEngine.ORPHAN_COUNT_UNKNOWN);
        assertThat(result.orphans().anyKept()).isTrue();
        assertThat(result.orphans().lines())
            .singleElement()
            .asString()
            .contains("an unknown number of unused player/world rows");
    }

    @Test
    void aFailingOrphanCountDoesNotFailThePurge() {
        StubDao dao = new StubDao(0L);
        dao.countFails = true;

        PurgeEngine.PurgeResult result = purge(dao);

        // The delete result stays authoritative: the purge happened.
        assertThat(result.deletedCount()).isEqualTo(4L);
        assertThat(result.mode()).isEqualTo(PurgeEngine.PurgeResult.Mode.DELETE);
        assertThat(result.orphans().remaining()).isEqualTo(PurgeEngine.ORPHAN_COUNT_UNKNOWN);
        assertThat(result.orphans().lines())
            .singleElement()
            .asString()
            .contains("could not count");
    }

    @Test
    void dryRunNeverAsksForOrphanCountsAndDeletesNothing() throws Exception {
        StubDao dao = new StubDao(9L);

        QueryFilter countOnly = aged().countOnly(true).build();
        PurgeEngine.PurgeResult result = new PurgeEngine(dao).purge(countOnly, MIN_AGE);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.deletedCount()).isZero();
        assertThat(dao.purgeCalls.get()).isZero();
        // A dry run strands nothing, so there is nothing to reclaim and nothing
        // to report — and the DAO must not be asked.
        assertThat(dao.orphanCountCalls.get()).isZero();
        assertThat(result.orphans().scanned()).isFalse();
        assertThat(result.orphans().lines()).isEmpty();
    }

    @Test
    void everyRealPurgeAsksTheDaoForResidue() {
        StubDao dao = new StubDao(0L);
        purge(dao);
        assertThat(dao.purgeCalls.get()).isEqualTo(1);
        assertThat(dao.orphanCountCalls.get()).isEqualTo(1);
    }

    @Test
    void singularAndPluralResidueReadCorrectly() {
        assertThat(new PurgeEngine.OrphanReport(true, 1L, 0L, 0L, 0L, "kept deliberately").lines())
            .containsExactly("[VonixGuardian] 1 unused player/world row remains after this purge: "
                + "kept deliberately.");
        assertThat(new PurgeEngine.OrphanReport(true, 2L, 0L, 0L, 0L, null).lines())
            .containsExactly("[VonixGuardian] 2 unused player/world rows remain after this purge: "
                + "reason not recorded.");
    }

    @Test
    void notApplicableIsSilent() {
        assertThat(PurgeEngine.OrphanReport.notApplicable().lines()).isEmpty();
        assertThat(PurgeEngine.OrphanReport.notApplicable().scanned()).isFalse();
    }

    // ------------------------------------------------------------------ fixtures

    private static PurgeEngine.PurgeResult purge(StubDao dao) {
        try {
            return new PurgeEngine(dao).purge(aged().build(), MIN_AGE);
        } catch (Exception ex) {
            throw new AssertionError("purge threw", ex);
        }
    }

    private static QueryFilter.Builder aged() {
        return QueryFilter.builder().sinceMillis(System.currentTimeMillis() - 40L * DAY_MS);
    }

    /**
     * Minimal DAO: records what the engine asked for and returns fixed numbers.
     * Not a database — these tests are about what the engine reports, not about
     * the SQL a backend runs.
     */
    private static final class StubDao implements GuardianDao {

        final AtomicInteger purgeCalls = new AtomicInteger();
        final AtomicInteger orphanCountCalls = new AtomicInteger();
        final long orphans;
        boolean countFails;

        StubDao(long orphans) {
            this.orphans = orphans;
        }

        @Override public void init() { }

        @Override public int insertBatch(List<Action> batch) {
            throw new UnsupportedOperationException();
        }

        @Override public List<Action> query(QueryFilter filter, int offset, int limit) {
            return List.of();
        }

        @Override public long count(QueryFilter filter) {
            return 4L;
        }

        @Override public int markRolledBack(List<Long> ids, boolean rolledBack) {
            throw new UnsupportedOperationException();
        }

        @Override public long purge(QueryFilter filter) {
            purgeCalls.incrementAndGet();
            return 4L;
        }

        @Override public OptimizeResult optimize(long maxRuntimeMillis) {
            return new OptimizeResult(0L, 0L, true);
        }

        @Override public long purgeOlderThan(long cutoffMillis, int chunkLimit) {
            throw new UnsupportedOperationException();
        }

        @Override public long countOrphanOwners() throws Exception {
            orphanCountCalls.incrementAndGet();
            if (countFails) {
                throw new Exception("identity tables unreadable");
            }
            return orphans;
        }

        @Override public int resolveUser(UUID uuid, String name) {
            throw new UnsupportedOperationException();
        }

        @Override public int resolveWorld(String key) {
            throw new UnsupportedOperationException();
        }

        @Override public long openRollbackBatch(UUID actorUuid, int mode, String filterJson,
                                                List<Long> actionIds) {
            throw new UnsupportedOperationException();
        }

        @Override public int closeRollbackBatch(long batchId) {
            throw new UnsupportedOperationException();
        }

        @Override public List<Long> findIncompleteBatchActionIds() {
            throw new UnsupportedOperationException();
        }

        @Override public boolean hasActionsInWindow(UUID user, String worldId, int x, int y, int z,
                                                    ActionType[] types, long withinMillis) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean isHealthy() { return true; }

        @Override public void close() { }
    }
}
