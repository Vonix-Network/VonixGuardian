package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-B2 regression: {@code /vg undo} must run the <em>inverse</em> operation
 * on the exact same action set (rollback ↔ restore), using
 * {@link RollbackResult#originalFilter()} + {@link RollbackResult#inverseMode()}.
 *
 * <p>This test drives the engine directly (bypassing the command layer) and
 * asserts the DAO's rolledBack flag toggles round-trip:
 * <ol>
 *   <li>rollback → {@code markRolledBack(ids, true)}</li>
 *   <li>simulate /vg undo (pop result → execute plan(originalFilter, inverseMode))
 *       → {@code markRolledBack(ids, false)}</li>
 * </ol>
 */
class UndoRevertTest {

    private GuardianDao dao;
    private RecordingMutator mutator;
    private Executor sync;
    private RollbackEngine engine;
    private UndoStack undoStack;
    private QueryFilter filter;
    private UUID actor;

    @BeforeEach
    void setUp() {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        sync = Runnable::run;
        engine = new RollbackEngine(dao, mutator, sync);
        undoStack = new UndoStack();
        actor = UUID.randomUUID();
        filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 86_400_000L)
            .build();
    }

    @Test
    void undoRevertsRollbackByRunningRestoreOnSameActionSet() throws Exception {
        // Fixture: a BLOCK_PLACE of stone at (5,64,7). SQL-side rolledBack
        // filtering: the engine issues query with rolledBack=false for
        // ROLLBACK and rolledBack=true for RESTORE — we mock accordingly.
        Action rowNotRolledBack = action(1L, 100L, ActionType.BLOCK_PLACE,
            "w", 5, 64, 7, "minecraft:stone", null, 1, false);
        Action rowRolledBack = action(1L, 100L, ActionType.BLOCK_PLACE,
            "w", 5, 64, 7, "minecraft:stone", null, 1, true);
        when(dao.query(argThat(qf -> qf != null && Boolean.FALSE.equals(qf.rolledBack())),
                       anyInt(), anyInt())).thenReturn(List.of(rowNotRolledBack));
        when(dao.query(argThat(qf -> qf != null && Boolean.TRUE.equals(qf.rolledBack())),
                       anyInt(), anyInt())).thenReturn(List.of(rowRolledBack));

        // 1) rollback — action gets flagged rolledBack=true in the DAO.
        RollbackResult rb = engine.rollback(filter, false, actor);
        assertThat(rb.mode()).isEqualTo(RollbackResult.Mode.ROLLBACK);
        assertThat(rb.affectedIds()).containsExactly(1L);
        assertThat(rb.originalFilter()).isNotNull();
        verify(dao).markRolledBack(List.of(1L), true);
        // rollback of BLOCK_PLACE = set air.
        assertThat(mutator.calls).containsExactly(
            "setBlock|w|5|64|7|minecraft:air|null"
        );

        // Command layer would push this result onto UndoStack.
        undoStack.push(actor, rb);
        assertThat(undoStack.history(actor)).hasSize(1);

        // 2) /vg undo: pop, then run inverse (RESTORE) on the popped
        // originalFilter — this is exactly what the new Undo.run does.
        var popped = undoStack.pop(actor).orElseThrow();
        assertThat(popped.inverseMode()).isEqualTo(RollbackResult.Mode.RESTORE);
        var plan = engine.plan(popped.originalFilter(), popped.inverseMode(), actor);
        RollbackResult undo = engine.execute(plan, false);

        assertThat(undo.mode()).isEqualTo(RollbackResult.Mode.RESTORE);
        assertThat(undo.affectedIds()).containsExactly(1L);
        // DAO flag toggled back off.
        verify(dao).markRolledBack(List.of(1L), false);
        // Restoring the BLOCK_PLACE re-places the stone.
        assertThat(mutator.calls).containsExactly(
            "setBlock|w|5|64|7|minecraft:air|null",
            "setBlock|w|5|64|7|minecraft:stone|null"
        );

        // Undo is one-shot: command layer does NOT re-push, so the stack
        // stays empty. Verified here at the collaborator level.
        assertThat(undoStack.history(actor)).isEmpty();
    }

    @Test
    void undoRevertsRestoreByRunningRollbackOnSameActionSet() throws Exception {
        // Symmetric direction: user just did a restore; undo should rollback.
        Action rowNotRolledBack = action(2L, 200L, ActionType.BLOCK_PLACE,
            "w", 1, 2, 3, "minecraft:dirt", null, 1, false);
        Action rowRolledBack = action(2L, 200L, ActionType.BLOCK_PLACE,
            "w", 1, 2, 3, "minecraft:dirt", null, 1, true);
        when(dao.query(argThat(qf -> qf != null && Boolean.TRUE.equals(qf.rolledBack())),
                       anyInt(), anyInt())).thenReturn(List.of(rowRolledBack));
        when(dao.query(argThat(qf -> qf != null && Boolean.FALSE.equals(qf.rolledBack())),
                       anyInt(), anyInt())).thenReturn(List.of(rowNotRolledBack));

        RollbackResult rs = engine.restore(filter, false, actor);
        assertThat(rs.mode()).isEqualTo(RollbackResult.Mode.RESTORE);
        assertThat(rs.originalFilter()).isNotNull();
        verify(dao).markRolledBack(List.of(2L), false);

        undoStack.push(actor, rs);
        var popped = undoStack.pop(actor).orElseThrow();
        assertThat(popped.inverseMode()).isEqualTo(RollbackResult.Mode.ROLLBACK);
        RollbackResult undo = engine.execute(
            engine.plan(popped.originalFilter(), popped.inverseMode(), actor),
            false);

        assertThat(undo.mode()).isEqualTo(RollbackResult.Mode.ROLLBACK);
        assertThat(undo.affectedIds()).containsExactly(2L);
        verify(dao).markRolledBack(List.of(2L), true);
    }

    @Test
    void undoStackKeepsDefaultPerActorCapOf20() {
        // W3-B2 rule 3: UndoStack cap unchanged.
        assertThat(UndoStack.MAX_PER_ACTOR).isEqualTo(20);
        for (int i = 0; i < 25; i++) {
            undoStack.push(actor, new RollbackResult(
                actor, RollbackResult.Mode.ROLLBACK, true,
                List.of((long) i), List.of(), 0, 0, filter));
        }
        assertThat(undoStack.history(actor)).hasSize(20);
    }

    // ------------------------------------------------------------- helpers

    private static Action action(long id, long ts, ActionType type,
                                 String world, int x, int y, int z,
                                 String targetId, String meta, int amount,
                                 boolean rolledBack) {
        return new Action(
            id, ts, type,
            UUID.randomUUID(), "Player",
            world, x, y, z,
            targetId, meta, amount, rolledBack, null
        );
    }

    private static final class RecordingMutator implements WorldMutator {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        @Override
        public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            calls.add("setBlock|" + worldId + "|" + x + "|" + y + "|" + z + "|" + targetId + "|" + targetMeta);
        }
        @Override
        public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
            calls.add("giveOrDrop|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount + "|" + targetMeta);
        }
        @Override
        public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
            calls.add("removeFromContainer|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount);
        }
        @Override
        public void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
            calls.add("respawnEntity|" + worldId + "|" + x + "|" + y + "|" + z + "|" + entityType + "|" + targetMeta);
        }
    }
}
