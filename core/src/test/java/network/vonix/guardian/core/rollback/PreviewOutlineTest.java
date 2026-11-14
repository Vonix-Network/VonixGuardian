package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A preview must report where it would change the world, not only how much.
 *
 * <p>An operator deciding whether to roll back a griefed base needs to see the
 * shape before committing. {@link PreviewOutline} carries those positions, capped
 * so a redstone farm cannot flood a client connection.</p>
 */
class PreviewOutlineTest {

    private static Action block(long id, int x, int y, int z) {
        return new ActionBuilder()
            .id(id)
            .type(ActionType.BLOCK_PLACE)
            .worldId("minecraft:overworld")
            .actorName("Griefer")
            .position(x, y, z)
            .targetId("minecraft:stone")
            .build();
    }

    private static List<Action> rows(int n) {
        List<Action> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(block(i + 1, i, 64, 0));
        }
        return out;
    }

    @Test
    void carriesEachPlannedPosition() {
        PreviewOutline o = PreviewOutline.fromPlanned(rows(3));
        assertThat(o.blocks()).hasSize(3);
        assertThat(o.total()).isEqualTo(3);
        assertThat(o.omitted()).isZero();
        assertThat(o.blocks().get(0).x()).isZero();
        assertThat(o.blocks().get(2).x()).isEqualTo(2);
        assertThat(o.blocks().get(0).worldId()).isEqualTo("minecraft:overworld");
        assertThat(o.blocks().get(0).blockId()).isEqualTo("minecraft:stone");
    }

    /**
     * The cap exists so a single preview of a large farm cannot send hundreds of
     * thousands of block updates. The full count must still be reported, or the
     * operator would think only 512 blocks would change.
     */
    @Test
    void capsPositionsButKeepsTheFullTotal() {
        PreviewOutline o = PreviewOutline.fromPlanned(rows(5_000), 512);
        assertThat(o.blocks()).hasSize(512);
        assertThat(o.total()).isEqualTo(5_000);
        assertThat(o.omitted()).isEqualTo(4_488);
    }

    @Test
    void emptyPlanProducesNothingToDraw() {
        assertThat(PreviewOutline.fromPlanned(List.of()).isEmpty()).isTrue();
        assertThat(PreviewOutline.empty().isEmpty()).isTrue();
        assertThat(PreviewOutline.empty().total()).isZero();
    }

    @Test
    void nullInputIsTreatedAsEmptyRatherThanThrowing() {
        assertThat(PreviewOutline.fromPlanned(null).isEmpty()).isTrue();
    }

    /** A zero or negative cap must not produce a negative-size list. */
    @Test
    void degenerateCapYieldsNoPositionsButKeepsTotal() {
        PreviewOutline o = PreviewOutline.fromPlanned(rows(4), 0);
        assertThat(o.blocks()).isEmpty();
        assertThat(o.total()).isEqualTo(4);
        assertThat(o.omitted()).isEqualTo(4);
    }

    /**
     * A non-preview result must carry an empty outline — a real rollback already
     * happened, so there is nothing to draw.
     */
    @Test
    void nonPreviewResultHasNoOutline() {
        RollbackResult r = new RollbackResult(
            java.util.UUID.randomUUID(), RollbackResult.Mode.ROLLBACK, false,
            List.of(), List.of(), 0, 0, network.vonix.guardian.core.query.QueryFilter.empty(),
            RollbackResult.Status.SUCCESS, 0L, 0, 0, 0, true, java.util.Map.of());
        assertThat(r.previewOutline().isEmpty())
            .as("the 15-arg constructor must default to an empty outline")
            .isTrue();
    }
}
