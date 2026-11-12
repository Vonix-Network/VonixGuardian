package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boot used to log one warning per action in an open batch, and nothing could
 * close that batch. One summary line, and close must not touch the world.
 */
class RollbackBatchesTest {

    @Test
    void recoveryNoticeIsOneLineForManyActionsAndSilentWhenNothingIsOpen() {
        List<GuardianDao.OpenRollbackBatch> open = List.of(
            new GuardianDao.OpenRollbackBatch(17, 0, 40),
            new GuardianDao.OpenRollbackBatch(18, 1, 2));
        List<Long> ids = LongStream.rangeClosed(1, 40).boxed().toList();

        List<String> lines = RollbackBatches.recoveryNotice(open, ids);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("17")
            .contains("18")
            .contains("40 actions")
            .contains("/vg batch")
            .contains("world was not changed")
            .doesNotContain("action id=");
        assertThat(RollbackBatches.recoveryNotice(List.of(), List.of())).isEmpty();
        assertThat(RollbackBatches.recoveryNotice(null, null)).isEmpty();
    }

    @Test
    void closeMarksTheAuditRowAndDoesNotMutate() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.findRollbackBatch(17L))
            .thenReturn(new GuardianDao.RollbackBatch(17L, 0, 3, true));
        when(dao.closeOpenRollbackBatch(17L)).thenReturn(1);

        List<String> lines = RollbackBatches.close(dao, 17L);

        assertThat(lines).containsExactly(
            "[VonixGuardian] Batch 17 is now closed (rollback, 3 actions). "
                + "The world was not changed. The logged actions are still in the audit log.");
        verify(dao).closeOpenRollbackBatch(17L);
        verify(dao, never()).closeRollbackBatch(anyLong());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
        verify(dao, never()).purge(any());
    }

    @Test
    void closeOfAMissingOrFinishedBatchDoesNothing() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.findRollbackBatch(4L))
            .thenReturn(new GuardianDao.RollbackBatch(4L, 1, 2, false));

        assertThat(RollbackBatches.close(dao, 4L).get(0))
            .contains("already closed")
            .contains("world was not changed");
        assertThat(RollbackBatches.close(dao, 99L).get(0))
            .contains("No batch 99")
            .contains("world was not changed");
        verify(dao, never()).closeOpenRollbackBatch(anyLong());
        verify(dao, never()).closeRollbackBatch(anyLong());
    }

    @Test
    void showNamesTheActionsWithoutChangingAnything() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.findRollbackBatch(17L))
            .thenReturn(new GuardianDao.RollbackBatch(17L, 0, 3, true));
        when(dao.findRollbackBatchActionIds(17L)).thenReturn(List.of(101L, 102L, 103L));

        List<String> lines = RollbackBatches.show(dao, 17L);

        assertThat(lines.get(0)).contains("Batch 17 is open").contains("world was not changed");
        assertThat(lines.get(1)).contains("101, 102, 103");
        assertThat(String.join("\n", lines)).contains("/vg batch close 17");
        verify(dao, never()).closeOpenRollbackBatch(anyLong());
        verify(dao, never()).closeRollbackBatch(anyLong());
    }
}
