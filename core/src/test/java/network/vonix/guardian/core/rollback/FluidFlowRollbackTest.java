/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
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
 * v1.3.1 X3 — regression: rolling back a {@link ActionType#FLUID_FLOW} row
 * clears the spread cell back to {@code minecraft:air} and restoring re-applies
 * the flowing fluid block.
 */
class FluidFlowRollbackTest {

    @Test
    void fluidFlowIsRollbackable() {
        assertThat(RollbackEngine.isRollbackable(ActionType.FLUID_FLOW)).isTrue();
    }

    @Test
    void planAdmitsFluidFlowRow() {
        Action a = fluidFlow(1L, 100L, 10, 64, 10, "minecraft:water");
        RollbackPlan plan = RollbackPlan.build(List.of(a), QueryFilter.empty(),
                RollbackResult.Mode.ROLLBACK, null);
        assertThat(plan.skipped()).isEmpty();
        assertThat(plan.ordered()).extracting(Action::type).containsExactly(ActionType.FLUID_FLOW);
    }

    @Test
    void rollbackFluidFlowClearsCellToAir() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        RecordingMutator mutator = new RecordingMutator();
        Executor sync = Runnable::run;
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(
                fluidFlow(1L, 100L, 10, 64, 10, "minecraft:water"),
                fluidFlow(2L, 99L, 11, 64, 10, "minecraft:lava")
        ));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L);

        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();
        RollbackResult result = engine.rollback(filter, false);

        assertThat(result.dispatchedSteps()).isEqualTo(2);
        assertThat(mutator.calls).containsExactly(
                "setBlock|w|10|64|10|minecraft:air|null",
                "setBlock|w|11|64|10|minecraft:air|null"
        );
    }

    @Test
    void restoreFluidFlowReappliesFluid() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        RecordingMutator mutator = new RecordingMutator();
        Executor sync = Runnable::run;
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        Action row = new Action(
                5L, 200L, ActionType.FLUID_FLOW,
                UUID.randomUUID(), "Alice",
                "w", 20, 63, 20,
                "minecraft:water", null, 0, true, "#fluid:water"
        );
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(row));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(2L);

        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).rolledBack(true).build();
        RollbackResult result = engine.restore(filter, false);

        assertThat(result.dispatchedSteps()).isEqualTo(1);
        assertThat(mutator.calls).containsExactly(
                "setBlock|w|20|63|20|minecraft:water|null"
        );
    }

    private static Action fluidFlow(long id, long ts, int x, int y, int z, String fluidId) {
        return new Action(
                id, ts, ActionType.FLUID_FLOW,
                null, "#fluid",
                "w", x, y, z,
                fluidId, null, 0, false, "#fluid:" + (fluidId.endsWith("lava") ? "lava" : "water")
        );
    }

    private static final class RecordingMutator implements WorldMutator {
        final List<String> calls = new ArrayList<>();

        @Override
        public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            calls.add("setBlock|" + worldId + "|" + x + "|" + y + "|" + z + "|" + targetId + "|" + targetMeta);
        }

        @Override
        public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
            calls.add("removeFromContainer|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount);
        }

        @Override
        public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String meta) {
            calls.add("giveOrDrop|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount + "|" + meta);
        }

        @Override
        public void respawnEntity(String worldId, int x, int y, int z, String entityType, String nbt) {
            calls.add("respawnEntity|" + worldId + "|" + x + "|" + y + "|" + z + "|" + entityType + "|" + nbt);
        }
    }
}
