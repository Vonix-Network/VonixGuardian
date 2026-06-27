package network.vonix.guardian.core.storage;

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
            assertThat(incomplete).containsExactlyElementsOf(ids(100));

            // And once we close it, recovery is clean.
            int closed = dao2.closeRollbackBatch(batchId);
            assertThat(closed).isEqualTo(1);
            assertThat(dao2.findIncompleteBatchActionIds()).isEmpty();
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
}
