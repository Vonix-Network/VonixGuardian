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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2-01 regression suite: proves the new two-phase (plan → execute) pipeline
 * in {@link RollbackEngine} really separates the two phases with no side
 * effects during {@code plan(...)}.
 */
class RollbackPlanTest {

    private GuardianDao dao;
    private RecordingMutator mutator;
    private RollbackEngine engine;
    private QueryFilter filter;

    @BeforeEach
    void setUp() {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        Executor sync = Runnable::run;
        engine = new RollbackEngine(dao, mutator, sync);
        filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 86_400_000L)
            .build();
    }

    // ---------------------------------------------------------------- plan()

    @Test
    void planWithZeroMatchesIsEmptyButTaggedWithContext() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        UUID actor = UUID.randomUUID();

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, actor);

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.plannedSteps()).isZero();
        assertThat(plan.actionIds()).isEmpty();
        assertThat(plan.skippedIds()).isEmpty();
        assertThat(plan.mode()).isEqualTo(RollbackResult.Mode.ROLLBACK);
        assertThat(plan.actorUuid()).isEqualTo(actor);
        assertThat(plan.originalFilter()).isNotNull();
        // rolledBack must be normalized to FALSE for rollback
        assertThat(plan.originalFilter().rolledBack()).isEqualTo(Boolean.FALSE);
    }

    @Test
    void planForRestoreNormalizesFilterToRolledBackTrue() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.RESTORE, null);
        assertThat(plan.originalFilter().rolledBack()).isEqualTo(Boolean.TRUE);
        assertThat(plan.mode()).isEqualTo(RollbackResult.Mode.RESTORE);
    }

    @Test
    void planPreservesWorldEditPlayerWhenNormalizingRolledBackState() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        UUID worldEditPlayer = UUID.randomUUID();
        QueryFilter weFilter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 86_400_000L)
            .worldEditPlayer(worldEditPlayer)
            .build();

        RollbackPlan rollback = engine.plan(weFilter, RollbackResult.Mode.ROLLBACK, null);
        RollbackPlan restore = engine.plan(weFilter, RollbackResult.Mode.RESTORE, null);

        assertThat(rollback.originalFilter().worldEditPlayer()).isEqualTo(worldEditPlayer);
        assertThat(restore.originalFilter().worldEditPlayer()).isEqualTo(worldEditPlayer);
    }

    @Test
    void planIsPureNoBatchOpenedNoMutatorCalledNoMarkings() throws Exception {
        Action place = action(1L, 100L, ActionType.BLOCK_PLACE,
            "w", 0, 64, 0, "minecraft:stone", null, 1, false);
        Action chat = action(2L, 101L, ActionType.CHAT,
            "w", 0, 64, 0, "hi", null, 0, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place, chat));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);

        // planned + skipped populated
        assertThat(plan.plannedSteps()).isEqualTo(1);
        assertThat(plan.actionIds()).containsExactly(1L);
        assertThat(plan.skippedIds()).containsExactly(2L);
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).action().id()).isEqualTo(1L);

        // ...but zero side effects.
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
        verify(dao, never()).closeRollbackBatch(anyLong());
    }

    @Test
    void planCanBeDiscardedWithoutSideEffects() throws Exception {
        Action place = action(3L, 100L, ActionType.BLOCK_PLACE,
            "w", 1, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        assertThat(plan.plannedSteps()).isEqualTo(1);
        // simply drop `plan` on the floor — no execute() call.

        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    // ------------------------------------------------------------- execute()

    @Test
    void executeAppliesTheAlreadyBuiltPlan() throws Exception {
        Action place = action(4L, 100L, ActionType.BLOCK_PLACE,
            "w", 2, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(42L);

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        RollbackResult r = engine.execute(plan, false);

        assertThat(r.dispatchedSteps()).isEqualTo(1);
        assertThat(r.affectedIds()).containsExactly(4L);
        assertThat(r.originalFilter()).isSameAs(plan.originalFilter());
        assertThat(mutator.calls).containsExactly(
            "setBlock|w|2|64|0|minecraft:air|null"
        );
        verify(dao).markRolledBack(List.of(4L), true);
        verify(dao).closeRollbackBatch(42L);
    }

    @Test
    void executePreviewLeavesEverythingAlone() throws Exception {
        Action place = action(5L, 100L, ActionType.BLOCK_PLACE,
            "w", 3, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        RollbackResult r = engine.execute(plan, true);

        assertThat(r.preview()).isTrue();
        assertThat(r.plannedSteps()).isEqualTo(1);
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    @Test
    void executeCanBeCalledTwiceOnSamePlanIndependently() throws Exception {
        Action a1 = action(6L, 100L, ActionType.BLOCK_PLACE,
            "w", 10, 64, 0, "minecraft:stone", null, 1, false);
        Action a2 = action(7L, 101L, ActionType.BLOCK_PLACE,
            "w", 11, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(a1, a2));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L, 2L);

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);

        // preview first, then real execute — no state carried between calls
        RollbackResult preview = engine.execute(plan, true);
        RollbackResult real = engine.execute(plan, false);

        assertThat(preview.dispatchedSteps()).isZero();
        assertThat(real.dispatchedSteps()).isEqualTo(2);
        assertThat(mutator.calls).hasSize(2);
    }

    @Test
    void executeRejectsPlanBuiltWithoutMode() {
        RollbackPlan legacy = RollbackPlan.build(List.of()); // no mode
        assertThatThrownBy(() -> engine.execute(legacy, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no mode");
    }

    @Test
    void executeRejectsNull() {
        assertThatThrownBy(() -> engine.execute(null, false))
            .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------- result plumbing

    @Test
    void resultCarriesOriginalFilterForUndoReplay() throws Exception {
        Action place = action(8L, 100L, ActionType.BLOCK_PLACE,
            "w", 4, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackResult r = engine.rollback(filter, false);

        // A2: originalFilter must be present on the result so /vg undo can
        // pop it and call restore(filter) later.
        assertThat(r.originalFilter()).isNotNull();
        assertThat(r.originalFilter().rolledBack()).isEqualTo(Boolean.FALSE);
        assertThat(r.inverseMode()).isEqualTo(RollbackResult.Mode.RESTORE);
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
