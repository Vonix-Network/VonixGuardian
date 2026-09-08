package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.command.CommandChatGuard;
import network.vonix.guardian.core.command.MutationOutcomeFormatter;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2.1.1 state-machine tests: one authoritative result per request, including
 * mixed outcomes, no-op setBlock, compensation, repair, undo stack, and
 * restart recovery.
 */
class RollbackResultTruthfulnessTest {

    private GuardianDao dao;
    private QueryFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(GuardianDao.class);
        filter = QueryFilter.builder()
                .sinceMillis(System.currentTimeMillis() - 86_400_000L)
                .build();
        when(dao.closeRollbackBatch(anyLong())).thenReturn(1);
    }

    @Test
    void allAppliedAndCloseOkIsSuccessNeverLaterError() throws Exception {
        Action place = action(1L, ActionType.BLOCK_PLACE);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(10L);
        RollbackEngine engine = new RollbackEngine(dao, new AlwaysApplyMutator(), Runnable::run);

        RollbackResult result = engine.rollback(filter, false);

        assertThat(result.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(result.batchClosed()).isTrue();
        assertThat(result.affectedIds()).containsExactly(1L);
        List<String> lines = MutationOutcomeFormatter.lines("Rollback", result, filter);
        assertThat(lines.get(0)).contains("SUCCESS").doesNotContain("error");
        verify(dao).markRolledBack(List.of(1L), true);
        verify(dao).closeRollbackBatch(10L);
    }

    @Test
    void allAppliedButCloseZeroIsPartialNotSuccess() throws Exception {
        Action place = action(2L, ActionType.BLOCK_PLACE);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(11L);
        when(dao.closeRollbackBatch(11L)).thenReturn(0);
        RollbackEngine engine = new RollbackEngine(dao, new AlwaysApplyMutator(), Runnable::run);

        RollbackResult result = engine.rollback(filter, false);

        assertThat(result.status()).isEqualTo(RollbackResult.Status.PARTIAL);
        assertThat(result.batchClosed()).isFalse();
        assertThat(result.affectedIds()).containsExactly(2L);
        assertThat(MutationOutcomeFormatter.lines("Rollback", result, filter).get(0))
                .contains("PARTIAL")
                .doesNotContain("Rollback world mutation did not complete successfully");
        verify(dao).markRolledBack(List.of(2L), true);
    }

    @Test
    void mixedAppliedAndSkippedIsSuccessWithCounts() throws Exception {
        Action place = action(3L, ActionType.BLOCK_PLACE);
        Action kill = action(4L, ActionType.ENTITY_KILL);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place, kill));
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(12L);
        RollbackEngine engine = new RollbackEngine(dao, new AlwaysApplyMutator(), Runnable::run);

        RollbackResult result = engine.restore(filter, false);

        assertThat(result.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(result.affectedIds()).containsExactly(3L);
        assertThat(result.skippedIds()).contains(4L);
        assertThat(result.batchClosed()).isTrue();
        String line = MutationOutcomeFormatter.lines("Restore", result, filter).get(0);
        assertThat(line).contains("SUCCESS").contains("skipped=").doesNotContain("Rollback error");
    }

    @Test
    void mixedAppliedAndFailedIsPartialWithCounts() throws Exception {
        Action a = action(5L, ActionType.BLOCK_PLACE, "w", 1, 64, 0);
        Action b = action(6L, ActionType.BLOCK_PLACE, "w", 2, 64, 0);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(a, b));
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(13L);
        WorldMutator mutator = new AlwaysApplyMutator() {
            @Override
            public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
                return x != 2;
            }
        };
        RollbackEngine engine = new RollbackEngine(dao, mutator, Runnable::run);

        RollbackResult result = engine.rollback(filter, false);

        assertThat(result.status()).isEqualTo(RollbackResult.Status.PARTIAL);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.batchClosed()).isFalse();
        verify(dao).markRolledBack(List.of(5L), true);
        verify(dao, never()).closeRollbackBatch(anyLong());
        assertThat(MutationOutcomeFormatter.lines("Rollback", result, filter).get(0))
                .contains("PARTIAL")
                .contains("failed=1")
                .doesNotContain("Rollback world mutation did not complete successfully");
    }

    @Test
    void alreadyCorrectSetBlockIsAppliedNotFailure() throws Exception {
        Action place = action(7L, ActionType.BLOCK_PLACE);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(14L);
        WorldMutator alreadyCorrect = new AlwaysApplyMutator() {
            @Override
            public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
                // Loader contract: vanilla setBlock false + already-matching state → true.
                return true;
            }
        };
        RollbackEngine engine = new RollbackEngine(dao, alreadyCorrect, Runnable::run);

        RollbackResult result = engine.rollback(filter, false);

        assertThat(result.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(result.affectedIds()).containsExactly(7L);
        verify(dao).markRolledBack(List.of(7L), true);
        verify(dao).closeRollbackBatch(14L);
    }

    @Test
    void zeroMatchPreviewAndExecuteAreSuccessNoOp() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        RollbackEngine engine = new RollbackEngine(dao, new AlwaysApplyMutator(), Runnable::run);

        RollbackResult preview = engine.rollback(filter, true);
        RollbackResult execute = engine.rollback(filter, false);

        assertThat(preview.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(preview.preview()).isTrue();
        assertThat(execute.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(execute.appliedCount()).isEqualTo(0);
        verify(dao, never()).openRollbackBatch(any(), anyInt(), any(), any());
    }

    @Test
    void recoverIncompleteBatchesDoesNotMutateOrChat() throws Exception {
        when(dao.findIncompleteBatchActionIds()).thenReturn(List.of(99L));
        AtomicInteger mutatorCalls = new AtomicInteger();
        WorldMutator mutator = new AlwaysApplyMutator() {
            @Override
            public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
                mutatorCalls.incrementAndGet();
                return super.trySetBlock(worldId, x, y, z, targetId, targetMeta);
            }
        };
        RollbackEngine engine = new RollbackEngine(dao, mutator, Runnable::run);

        engine.recoverIncompleteBatches();

        assertThat(mutatorCalls.get()).isZero();
        verify(dao, never()).closeRollbackBatch(anyLong());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    @Test
    void undoPopIfSameLeavesStackOnFailureIdentity() {
        UndoStack stack = new UndoStack();
        UUID actor = UUID.randomUUID();
        RollbackResult first = new RollbackResult(actor, RollbackResult.Mode.ROLLBACK, false,
                List.of(1L), List.of(), 1, 1);
        stack.push(actor, first);
        RollbackResult peeked = stack.peek(actor).orElseThrow();
        assertThat(stack.popIfSame(actor, new RollbackResult(actor, RollbackResult.Mode.ROLLBACK, false,
                List.of(1L), List.of(), 1, 1))).isEmpty();
        assertThat(stack.peek(actor)).contains(peeked);
        assertThat(stack.popIfSame(actor, peeked)).contains(peeked);
        assertThat(stack.peek(actor)).isEmpty();
    }

    @Test
    void staleChatGenerationIsSuppressed() {
        UUID actor = UUID.randomUUID();
        long first = CommandChatGuard.next(actor);
        long second = CommandChatGuard.next(actor);
        assertThat(CommandChatGuard.isCurrent(actor, first)).isFalse();
        assertThat(CommandChatGuard.isCurrent(actor, second)).isTrue();
    }

    @Test
    void silentSuccessEmitsNoLinesVerboseAddsDetail() {
        RollbackResult success = new RollbackResult(null, RollbackResult.Mode.ROLLBACK, false,
                List.of(1L), List.of(), 1, 1, filter, RollbackResult.Status.SUCCESS, 8L, 0, 0, 0, true);
        QueryFilter silent = QueryFilter.builder().sinceMillis(1L).silent(true).build();
        QueryFilter verbose = QueryFilter.builder().sinceMillis(1L).verbose(true).build();
        assertThat(MutationOutcomeFormatter.lines("Rollback", success, silent)).isEmpty();
        assertThat(MutationOutcomeFormatter.lines("Rollback", success, verbose))
                .hasSizeGreaterThanOrEqualTo(2)
                .anyMatch(l -> l.contains("verbose"));
        RollbackResult partial = new RollbackResult(null, RollbackResult.Mode.RESTORE, false,
                List.of(1L), List.of(), 2, 1, filter, RollbackResult.Status.PARTIAL, 9L, 1, 0, 0, false);
        assertThat(MutationOutcomeFormatter.lines("Restore", partial, silent))
                .isNotEmpty()
                .allMatch(l -> l.contains("PARTIAL"));
        assertThat(MutationOutcomeFormatter.summaryLine("Restore", partial)).contains("PARTIAL");
        RollbackResult preview = new RollbackResult(null, RollbackResult.Mode.RESTORE, true,
                List.of(1L), List.of(), 1, 0, filter);
        assertThat(MutationOutcomeFormatter.summaryLine("Restore", preview)).contains("(preview)");
    }

    private static Action action(long id, ActionType type) {
        return action(id, type, "w", 1, 64, 1);
    }

    private static Action action(long id, ActionType type, String world, int x, int y, int z) {
        return new Action(
                id, 100L, type, UUID.randomUUID(), "Player",
                world, x, y, z, "minecraft:stone", null, 1, type == ActionType.ENTITY_KILL, null);
    }

    private static class AlwaysApplyMutator implements WorldMutator {
        @Override
        public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            return true;
        }
    }
}
