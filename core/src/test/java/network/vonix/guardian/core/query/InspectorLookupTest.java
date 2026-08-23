package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InspectorLookupTest {

    private static final String WORLD = "minecraft:overworld";
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void formatsRowsWithExplicitOperatorFields() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.query(any(QueryFilter.class), eq(0), eq(10))).thenReturn(List.of(
                action(NOW - 120_000L, ActionType.BLOCK_BREAK, "Vibegon",
                        "minecraft:short_grass", false),
                action(NOW - 3_600_000L, ActionType.HANGING_PLACE, null,
                        "minecraft:painting", true)
        ));

        List<String> lines = InspectorLookup.lookup(dao, WORLD, 249, 68, 444, 10, NOW);

        assertThat(lines).containsExactly(
                "----- VonixGuardian | Block history | world=minecraft:overworld | pos=(249, 68, 444) -----",
                "2m ago | Vibegon | broke | block=minecraft:short_grass | at (249, 68, 444)",
                "1h ago | #unknown | placed | hanging=minecraft:painting | at (249, 68, 444) | rolled back"
        );
    }

    @Test
    void reportsWorldAndNoHistoryWithoutDebugStyleCoordinates() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.query(any(QueryFilter.class), eq(0), eq(1))).thenReturn(List.of());

        List<String> lines = InspectorLookup.lookup(dao, null, -12, 70, 345, 0, NOW);

        assertThat(lines).containsExactly(
                "----- VonixGuardian | Block history | world=#unknown | pos=(-12, 70, 345) -----",
                "No history found for this block."
        );
    }

    @Test
    void preservesTheExactPositionAndWorldInTheDaoFilter() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        when(dao.query(any(QueryFilter.class), eq(0), eq(10))).thenReturn(List.of());

        InspectorLookup.lookup(dao, "modded:moon", -12, 70, 345, 10, NOW);

        var filter = org.mockito.ArgumentCaptor.forClass(QueryFilter.class);
        org.mockito.Mockito.verify(dao).query(filter.capture(), eq(0), eq(10));
        QueryFilter actual = filter.getValue();
        assertThat(actual.worldSel().worldKey()).isEqualTo("modded:moon");
        assertThat(actual.centerX()).isEqualTo(-12);
        assertThat(actual.centerY()).isEqualTo(70);
        assertThat(actual.centerZ()).isEqualTo(345);
        assertThat(actual.radius()).isZero();
    }

    private static Action action(long timestamp, ActionType type, String actorName,
                                 String targetId, boolean rolledBack) {
        return new Action(-1L, timestamp, type,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), actorName,
                WORLD, 249, 68, 444, targetId, null, 1, rolledBack, null);
    }
}
