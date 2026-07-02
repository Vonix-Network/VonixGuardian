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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollbackEngineTest {

    private GuardianDao dao;
    private RecordingMutator mutator;
    private Executor sync;
    private RollbackEngine engine;
    private QueryFilter filter;

    @BeforeEach
    void setUp() {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        sync = Runnable::run; // synchronous "main thread"
        engine = new RollbackEngine(dao, mutator, sync);
        filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 86_400_000L)
            .build();
    }

    // ------------------------------------------------------------------ guards

    @Test
    void rollbackAndRestoreRequireExplicitTimeFilter() throws Exception {
        QueryFilter unbounded = QueryFilter.empty();

        assertThatThrownBy(() -> engine.rollback(unbounded, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("time filter");
        assertThatThrownBy(() -> engine.restore(unbounded, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("time filter");
        verify(dao, never()).query(any(), anyInt(), anyInt());
    }

    // ------------------------------------------------------------------ ctor

    @Test
    void ctorRejectsNulls() {
        assertThatThrownBy(() -> new RollbackEngine(null, mutator, sync))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RollbackEngine(dao, null, sync))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RollbackEngine(dao, mutator, null))
            .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------- rollback path

    @Test
    void rollbackEmptyResultIsNoOp() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.affectedCount()).isZero();
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    @Test
    void rollbackBlockPlaceSetsAir() throws Exception {
        Action place = action(1L, 100L, ActionType.BLOCK_PLACE,
            "minecraft:overworld", 5, 64, 7,
            "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.preview()).isFalse();
        assertThat(r.affectedIds()).containsExactly(1L);
        assertThat(r.dispatchedSteps()).isEqualTo(1);
        assertThat(mutator.calls).containsExactly(
            "setBlock|minecraft:overworld|5|64|7|minecraft:air|null"
        );
        verify(dao).markRolledBack(List.of(1L), true);
    }

    @Test
    void rollbackBlockBreakSetsTargetWithMeta() throws Exception {
        Action brk = action(2L, 100L, ActionType.BLOCK_BREAK,
            "minecraft:overworld", 1, 2, 3,
            "minecraft:diamond_ore", "{\"state\":\"x\"}", 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(brk));

        engine.rollback(filter, false);

        assertThat(mutator.calls).containsExactly(
            "setBlock|minecraft:overworld|1|2|3|minecraft:diamond_ore|{\"state\":\"x\"}"
        );
    }

    @Test
    void rollbackContainerDepositRemoves() throws Exception {
        Action dep = action(3L, 100L, ActionType.CONTAINER_DEPOSIT,
            "w", 0, 0, 0, "minecraft:diamond", null, 5, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(dep));

        engine.rollback(filter, false);

        assertThat(mutator.calls).containsExactly(
            "removeFromContainer|w|0|0|0|minecraft:diamond|5"
        );
    }

    @Test
    void rollbackContainerWithdrawGives() throws Exception {
        Action w = action(4L, 100L, ActionType.CONTAINER_WITHDRAW,
            "w", 0, 0, 0, "minecraft:diamond", null, 3, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(w));

        engine.rollback(filter, false);

        assertThat(mutator.calls).containsExactly(
            "giveOrDrop|w|0|0|0|minecraft:diamond|3|null"
        );
    }

    @Test
    void rollbackEntityKillRespawns() throws Exception {
        Action k = action(5L, 100L, ActionType.ENTITY_KILL,
            "w", 1, 2, 3, "minecraft:zombie", "{\"hp\":20}", 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(k));

        engine.rollback(filter, false);

        assertThat(mutator.calls).containsExactly(
            "respawnEntity|w|1|2|3|minecraft:zombie|{\"hp\":20}"
        );
    }

    @Test
    void rollbackExplosionRestoresEachBlock() throws Exception {
        String target = "0:64:0=minecraft:stone,1:64:0=minecraft:diamond_ore|{\"x\":1}";
        Action boom = action(6L, 100L, ActionType.EXPLOSION,
            "w", 0, 64, 0, target, null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(boom));

        engine.rollback(filter, false);

        assertThat(mutator.calls).containsExactly(
            "setBlock|w|0|64|0|minecraft:stone|null",
            "setBlock|w|1|64|0|minecraft:diamond_ore|{\"x\":1}"
        );
    }

    @Test
    void rollbackSkipsNonRollbackableAndWarns() throws Exception {
        Action chat = action(7L, 100L, ActionType.CHAT,
            "w", 0, 0, 0, "hello", null, 0, false);
        Action place = action(8L, 101L, ActionType.BLOCK_PLACE,
            "w", 1, 1, 1, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(chat, place));

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.affectedIds()).containsExactly(8L);
        assertThat(r.skippedIds()).containsExactly(7L);
        assertThat(mutator.calls).hasSize(1);
    }

    @Test
    void rollbackIgnoresAlreadyRolledBackRows() throws Exception {
        // SQL-side filtering: engine calls dao with rolledBack=FALSE, so the DAO
        // would not return a rolledBack=true row in production. We simulate that
        // by returning an empty list when the filter requests rolledBack=FALSE.
        when(dao.query(argThat(qf -> qf != null && Boolean.FALSE.equals(qf.rolledBack())),
                       anyInt(), anyInt())).thenReturn(List.of());

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.affectedIds()).isEmpty();
        assertThat(mutator.calls).isEmpty();
    }

    @Test
    void rollbackDedupesByPositionNewestWins() throws Exception {
        // older break of diamond, then newer place of stone at same pos.
        // Plan should keep only the newer (place) action.
        Action breakOlder = action(10L, 50L, ActionType.BLOCK_BREAK,
            "w", 1, 1, 1, "minecraft:diamond_ore", null, 1, false);
        Action placeNewer = action(11L, 200L, ActionType.BLOCK_PLACE,
            "w", 1, 1, 1, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(breakOlder, placeNewer));

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.affectedIds()).containsExactly(11L);
        assertThat(mutator.calls).containsExactly(
            "setBlock|w|1|1|1|minecraft:air|null"
        );
    }

    @Test
    void previewRollbackDoesNotMutateOrTouchDao() throws Exception {
        Action place = action(12L, 100L, ActionType.BLOCK_PLACE,
            "w", 0, 0, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackResult r = engine.rollback(filter, true);

        assertThat(r.preview()).isTrue();
        assertThat(r.plannedSteps()).isEqualTo(1);
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    @Test
    void rollbackBatchingDispatchesInChunks() throws Exception {
        // Build 2500 BLOCK_PLACE actions at distinct positions
        List<Action> page1 = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            page1.add(action(1000L + i, 100L + i, ActionType.BLOCK_PLACE,
                "w", i, 64, 0, "minecraft:stone", null, 1, false));
        }
        when(dao.query(any(), eq(0), anyInt())).thenReturn(page1);
        when(dao.query(any(), eq(5000), anyInt())).thenReturn(List.of());

        CountingExecutor exec = new CountingExecutor();
        RollbackEngine batched = new RollbackEngine(dao, mutator, exec);
        RollbackResult r = batched.rollback(filter, false);

        assertThat(r.dispatchedSteps()).isEqualTo(2500);
        // 2500 / 1000 = 3 batches
        assertThat(exec.runCount).isEqualTo(3);
        assertThat(mutator.calls).hasSize(2500);
    }

    @Test
    void rollbackTaggedWithActor() throws Exception {
        UUID actor = UUID.randomUUID();
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        RollbackResult r = engine.rollback(filter, false, actor);
        assertThat(r.actorUuid()).isEqualTo(actor);
        assertThat(r.mode()).isEqualTo(RollbackResult.Mode.ROLLBACK);
    }

    // ---------------------------------------------------------- restore path

    @Test
    void restoreOnlyTouchesRolledBackRows() throws Exception {
        // SQL-side filtering: restore() builds a filter with rolledBack=TRUE so
        // the DAO only returns rolledBack=true rows in production. The mock here
        // returns only the rolled-back row when the filter requests it.
        Action placeUndone = action(21L, 100L, ActionType.BLOCK_PLACE,
            "w", 1, 1, 1, "minecraft:stone", null, 1, true);
        when(dao.query(argThat(qf -> qf != null && Boolean.TRUE.equals(qf.rolledBack())),
                       anyInt(), anyInt())).thenReturn(List.of(placeUndone));

        RollbackResult r = engine.restore(filter, false);

        assertThat(r.affectedIds()).containsExactly(21L);
        // restoring a BLOCK_PLACE = re-place
        assertThat(mutator.calls).containsExactly(
            "setBlock|w|1|1|1|minecraft:stone|null"
        );
        verify(dao).markRolledBack(List.of(21L), false);
    }

    @Test
    void restoreBlockBreakClearsToAir() throws Exception {
        Action brk = action(22L, 100L, ActionType.BLOCK_BREAK,
            "w", 2, 3, 4, "minecraft:diamond_ore", null, 1, true);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(brk));

        engine.restore(filter, false);

        assertThat(mutator.calls).containsExactly(
            "setBlock|w|2|3|4|minecraft:air|null"
        );
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

    /** Records mutator invocations as flat strings for trivial equality assertions. */
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

    private static final class CountingExecutor implements Executor {
        int runCount;
        @Override
        public void execute(Runnable command) {
            runCount++;
            command.run();
        }
    }
}
