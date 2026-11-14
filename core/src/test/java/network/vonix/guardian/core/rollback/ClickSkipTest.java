package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code a:click} selects interaction rows. A click changes no world state — the
 * effect it caused is recorded as its own row — so a rollback must report it as
 * skipped with a reason that says exactly that, instead of folding it in with
 * chat or silently dropping it.
 */
class ClickSkipTest {

    @Test
    void clickRowsAreSkippedWithInteractionOnlyReason() {
        Action click = new ActionBuilder()
            .id(1L)
            .type(ActionType.CLICK)
            .worldId("minecraft:overworld")
            .actorName("Player")
            .position(1, 64, 1)
            .targetId("minecraft:oak_door")
            .build();

        RollbackPlan plan = RollbackPlan.build(List.of(click), QueryFilter.empty(),
            RollbackResult.Mode.ROLLBACK, UUID.randomUUID());

        assertThat(plan.ordered())
            .as("a click must never be dispatched as a world mutation")
            .isEmpty();
        assertThat(plan.skippedEntries()).hasSize(1);
        assertThat(plan.skippedEntries().get(0).reason())
            .isEqualTo(RollbackResult.SkipReason.INTERACTION_ONLY);
    }

    /** The operator-facing text must distinguish a click from chat. */
    @Test
    void interactionSkipIsNamedDistinctlyFromAuditOnly() {
        RollbackResult result = new RollbackResult(
            UUID.randomUUID(), RollbackResult.Mode.ROLLBACK, true,
            List.of(), List.of(1L, 2L, 3L), 0, 0, QueryFilter.empty(),
            RollbackResult.Status.SUCCESS, 0L, 0, 0, 0, false,
            Map.of(RollbackResult.SkipReason.INTERACTION_ONLY, 3));

        String detail = result.skipReasonDetail();
        assertThat(detail).contains("clicks");
        assertThat(detail)
            .as("chat and clicks must not share one bucket")
            .doesNotContain("chat/session");
    }
}
