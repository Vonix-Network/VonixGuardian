package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for CP/Ledger parity expansion action rollback. These tests
 * intentionally prove that the plan admits the action families the engine
 * already has concrete mutation code for, while audit-only/unsafe families stay
 * skipped instead of silently pretending to roll back.
 */
class RollbackExpansionParityTest {

    @Test
    void planAdmitsExpansionActionsThatRollbackEngineCanMutate() {
        List<ActionType> supported = List.of(
            ActionType.BURN,
            ActionType.IGNITE,
            ActionType.FADE,
            ActionType.FORM,
            ActionType.SPREAD,
            ActionType.BUCKET_EMPTY,
            ActionType.BUCKET_FILL,
            ActionType.LEAVES_DECAY,
            ActionType.ENTITY_CHANGE_BLOCK,
            ActionType.HOPPER_PUSH,
            ActionType.HOPPER_PULL,
            ActionType.HANGING_PLACE,
            ActionType.HANGING_BREAK,
            ActionType.STRUCTURE_GROW,
            ActionType.PORTAL_CREATE,
            ActionType.FLUID_FLOW
        );
        List<Action> actions = new ArrayList<>();
        long id = 1;
        for (ActionType type : supported) {
            actions.add(action(id++, id, type, id));
        }

        RollbackPlan plan = RollbackPlan.build(actions, QueryFilter.empty(), RollbackResult.Mode.ROLLBACK, null);

        assertThat(plan.skipped()).isEmpty();
        assertThat(plan.ordered()).extracting(Action::type).containsExactlyInAnyOrderElementsOf(supported);
    }

    @Test
    void planSkipsAuditOnlyOrUnsafeExpansionActions() {
        List<ActionType> unsupported = List.of(
            ActionType.DISPENSE,
            ActionType.PISTON_EXTEND,
            ActionType.PISTON_RETRACT,
            ActionType.INVENTORY_DEPOSIT,
            ActionType.INVENTORY_WITHDRAW,
            ActionType.ITEM_CRAFT,
            ActionType.ENTITY_SPAWN,
            ActionType.ENTITY_INTERACT,
            ActionType.CHUNK_POPULATE,
            ActionType.CLICK,
            ActionType.CHAT,
            ActionType.COMMAND,
            ActionType.SIGN,
            ActionType.SESSION_JOIN,
            ActionType.SESSION_LEAVE,
            ActionType.USERNAME_CHANGE
        );
        List<Action> actions = new ArrayList<>();
        long id = 100;
        for (ActionType type : unsupported) {
            actions.add(action(id++, id, type, id));
        }

        RollbackPlan plan = RollbackPlan.build(actions, QueryFilter.empty(), RollbackResult.Mode.ROLLBACK, null);

        assertThat(plan.ordered()).isEmpty();
        assertThat(plan.skipped()).extracting(Action::type).containsExactlyInAnyOrderElementsOf(unsupported);
    }

    @Test
    void rollbackExpansionActionsInvokeExpectedMutatorOperations() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        RecordingMutator mutator = new RecordingMutator();
        Executor sync = Runnable::run;
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(
            action(1, 100, ActionType.BURN, 1),
            action(2, 99, ActionType.FORM, 2),
            action(3, 98, ActionType.ENTITY_CHANGE_BLOCK, 3),
            action(4, 97, ActionType.HOPPER_PUSH, 4),
            action(5, 96, ActionType.HOPPER_PULL, 5)
        ));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(77L);

        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();
        RollbackResult result = engine.rollback(filter, false);

        assertThat(result.dispatchedSteps()).isEqualTo(5);
        assertThat(mutator.calls).containsExactly(
            "setBlock|w|1|64|1|target-BURN|meta-BURN",
            "setBlock|w|2|64|2|minecraft:air|null",
            "setBlock|w|3|64|3|target-ENTITY_CHANGE_BLOCK|null",
            "removeFromContainer|w|4|64|4|target-HOPPER_PUSH|4",
            "giveOrDrop|w|5|64|5|target-HOPPER_PULL|5|meta-HOPPER_PULL"
        );
        org.mockito.Mockito.verify(dao).markRolledBack(List.of(1L, 2L, 3L, 4L, 5L), true);
        org.mockito.Mockito.verify(dao).closeRollbackBatch(77L);
    }

    @Test
    void engineAndPlanRollbackableContractsStayInSync() {
        for (ActionType t : ActionType.values()) {
            Action a = action(t.id(), 1_000 + t.id(), t, t.id());
            assertThat(RollbackPlan.isRollbackable(a))
                .as("RollbackPlan.isRollbackable(%s) should match RollbackEngine.isRollbackable(%s)", t, t)
                .isEqualTo(RollbackEngine.isRollbackable(t));
        }
    }

    private static Action action(long id, long ts, ActionType type, long coord) {
        return new Action(
            id, ts, type,
            UUID.randomUUID(), "Actor",
            "w", (int) coord, 64, (int) coord,
            "target-" + type.name(), "meta-" + type.name(), (int) Math.max(1, coord), false, null
        );
    }

    private static final class RecordingMutator implements WorldMutator {
        final List<String> calls = new ArrayList<>();

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
        public void respawnEntity(String worldId, int x, int y, int z, String entityType, String nbt) {
            calls.add("respawnEntity|" + worldId + "|" + x + "|" + y + "|" + z + "|" + entityType + "|" + nbt);
        }
    }
}
