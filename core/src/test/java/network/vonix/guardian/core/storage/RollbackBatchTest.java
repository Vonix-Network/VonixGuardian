package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.rollback.RollbackBatches;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash-recovery audit table tests: {@link GuardianDao#openRollbackBatch},
 * {@link GuardianDao#closeRollbackBatch}, and
 * {@link GuardianDao#findIncompleteBatchActionIds}.
 */
class RollbackBatchTest {

    private static List<Long> ids(int n) {
        List<Long> out = new ArrayList<>(n);
        for (long i = 1; i <= n; i++) out.add(i);
        return out;
    }

    @Test
    void opened_then_closed_batch_is_not_returned_as_incomplete() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        try {
            dao.init();
            long batchId = dao.openRollbackBatch(UUID.randomUUID(), 0, "{}", ids(100));
            assertThat(batchId).isPositive();

            int closed = dao.closeRollbackBatch(batchId);
            assertThat(closed).isEqualTo(1);

            List<Long> incomplete = dao.findIncompleteBatchActionIds();
            assertThat(incomplete).isEmpty();
        } finally {
            dao.close();
        }
    }

    @Test
    void crash_sim_recovers_action_ids_from_incomplete_batch() throws Exception {
        // Use an on-disk file so the data survives dao.close()/reopen.
        Path tmp = Files.createTempFile("vg-rollback-batch-", ".db");
        Files.deleteIfExists(tmp);
        String url = "jdbc:sqlite:" + tmp.toAbsolutePath();

        // Phase 1: open a batch with 100 ids, then close the DAO WITHOUT marking it complete.
        SqliteDao dao1 = new SqliteDao(url);
        long batchId;
        try {
            dao1.init();
            batchId = dao1.openRollbackBatch(null, 1, null, ids(100));
            assertThat(batchId).isPositive();
        } finally {
            dao1.close();
        }

        // Phase 2: reopen — the previously opened batch must be visible as incomplete.
        SqliteDao dao2 = new SqliteDao(url);
        try {
            dao2.init();
            List<Long> incomplete = dao2.findIncompleteBatchActionIds();
            assertThat(incomplete).hasSize(100);
            assertThat(dao2.findOpenRollbackBatches(20))
                    .extracting(GuardianDao.OpenRollbackBatch::id, GuardianDao.OpenRollbackBatch::mode,
                            GuardianDao.OpenRollbackBatch::affected)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(batchId, 1, 100));
            assertThat(incomplete).containsExactlyElementsOf(ids(100));

            // And once we close it, recovery is clean.
            int closed = dao2.closeRollbackBatch(batchId);
            assertThat(closed).isEqualTo(1);
            assertThat(dao2.findIncompleteBatchActionIds()).isEmpty();
            assertThat(dao2.findOpenRollbackBatches(20)).isEmpty();
        } finally {
            dao2.close();
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void empty_action_ids_batch_is_still_recorded() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        try {
            dao.init();
            long batchId = dao.openRollbackBatch(null, 0, null, List.of());
            assertThat(batchId).isPositive();
            assertThat(dao.findIncompleteBatchActionIds()).isEmpty();
            dao.closeRollbackBatch(batchId);
        } finally {
            dao.close();
        }
    }

    @Test
    void closing_an_open_batch_keeps_its_action_ids_and_a_second_close_does_nothing() throws Exception {
        SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:");
        try {
            dao.init();
            long batchId = dao.openRollbackBatch(null, 0, "{}", ids(3));
            assertThat(RollbackBatches.list(dao).get(0)).contains("1 open rollback batch");
            assertThat(RollbackBatches.show(dao, batchId).get(1)).contains("1, 2, 3");

            List<String> closed = RollbackBatches.close(dao, batchId);

            assertThat(closed).hasSize(1);
            assertThat(closed.get(0)).contains("now closed").contains("world was not changed");
            assertThat(dao.findOpenRollbackBatches(20)).isEmpty();
            assertThat(dao.findIncompleteBatchActionIds()).isEmpty();
            assertThat(dao.findRollbackBatchActionIds(batchId)).containsExactly(1L, 2L, 3L);
            assertThat(dao.findRollbackBatch(batchId).open()).isFalse();
            assertThat(dao.closeOpenRollbackBatch(batchId)).isZero();
            assertThat(RollbackBatches.close(dao, batchId).get(0)).contains("already closed");
            assertThat(RollbackBatches.show(dao, batchId).get(0)).contains("already closed");
            assertThat(RollbackBatches.list(dao).get(0)).contains("No open rollback batches");
        } finally {
            dao.close();
        }
    }
}
