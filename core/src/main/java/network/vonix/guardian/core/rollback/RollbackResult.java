package network.vonix.guardian.core.rollback;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a {@link RollbackEngine#rollback} or {@link RollbackEngine#restore}
 * invocation. Returned to the caller (and pushed onto the per-actor
 * {@link UndoStack} when applicable).
 *
 * @param actorUuid     who triggered the operation; may be {@code null} for console
 * @param mode          {@link Mode#ROLLBACK} or {@link Mode#RESTORE}
 * @param preview       {@code true} when no mutations were dispatched
 * @param affectedIds   action IDs that were (or would be, if preview) toggled
 * @param skippedIds    action IDs excluded from the plan because their type is not rollbackable
 * @param plannedSteps  number of world mutations the plan would dispatch
 * @param dispatchedSteps number of world mutations actually submitted to the executor
 *                        ({@code 0} for previews)
 */
public record RollbackResult(
    UUID actorUuid,
    Mode mode,
    boolean preview,
    List<Long> affectedIds,
    List<Long> skippedIds,
    int plannedSteps,
    int dispatchedSteps
) {

    /** Operation kind. */
    public enum Mode { ROLLBACK, RESTORE }

    public RollbackResult {
        Objects.requireNonNull(mode, "mode");
        affectedIds = affectedIds == null ? List.of() : List.copyOf(affectedIds);
        skippedIds = skippedIds == null ? List.of() : List.copyOf(skippedIds);
        if (plannedSteps < 0) {
            throw new IllegalArgumentException("plannedSteps < 0");
        }
        if (dispatchedSteps < 0) {
            throw new IllegalArgumentException("dispatchedSteps < 0");
        }
    }

    /** Total actions touched (rolled back or restored). */
    public int affectedCount() {
        return affectedIds.size();
    }
}
