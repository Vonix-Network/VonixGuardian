package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Paired fire/break rollback: a radius query that only sees the ignite row
 * still pulls the sibling entity-change via durable pairId, which is how
 * pairing survives persistence/restart after FireCauserMemory is gone.
 */
class PairedRollbackRestartTest {

    @Test
    void rollback_of_paired_ignite_also_restores_sibling_break() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        RecordingMutator mutator = new RecordingMutator();
        Executor sync = Runnable::run;
        RollbackEngine engine = new RollbackEngine(dao, mutator, sync);
        QueryFilter filter = QueryFilter.builder()
                .sinceMillis(1L)
                .build();
        when(dao.closeRollbackBatch(anyLong())).thenReturn(1);

        UUID actor = UUID.randomUUID();
        long pair = 77L;
        Action ignite = new Action(10L, 200L, ActionType.IGNITE, actor, "Dragon",
                "w", 11, 64, 20, "minecraft:fire", null, 1, false, "entity:#entity",
                null, null, null, null, null, null, null, null, pair);
        Action brk = new Action(9L, 100L, ActionType.ENTITY_CHANGE_BLOCK, actor, "Dragon",
                "w", 10, 64, 20, "minecraft:oak_log", "minecraft:air", 1, false, "#entity",
                null, null, null, null, null, null, null, null, pair);

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(ignite));
        when(dao.queryPage(any(), anyInt(), anyInt()))
                .thenReturn(new GuardianDao.QueryPage(List.of(ignite), false));
        when(dao.findByPairIds(anyCollection())).thenReturn(List.of(ignite, brk));

        engine.rollback(filter, false);

        assertThat(mutator.calls).contains(
                "setBlock|w|11|64|20|minecraft:air|null",
                "setBlock|w|10|64|20|minecraft:oak_log|null"
        );
    }

    private static final class RecordingMutator implements WorldMutator {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            calls.add("setBlock|" + worldId + "|" + x + "|" + y + "|" + z + "|" + targetId + "|" + targetMeta);
            return true;
        }
    }
}
