package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.query.QueryFilter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a {@link RollbackEngine#rollback} or {@link RollbackEngine#restore}
 * invocation. Returned to the caller (and pushed onto the per-actor
 * {@link UndoStack} when applicable).
 *
 * <p>W2-01 refactor (v1.1.6): now carries the {@link #originalFilter()} that
 * built the plan. This lets {@code /vg undo} pop an entry and call the
 * <em>inverse</em> operation ({@link RollbackEngine#restore} after a rollback,
 * or {@link RollbackEngine#rollback} after a restore) on exactly the same
 * action set, without having to reconstruct the filter from user text.</p>
 *
 * @param actorUuid       who triggered the operation; may be {@code null} for console
 * @param mode            {@link Mode#ROLLBACK} or {@link Mode#RESTORE}
 * @param preview         {@code true} when no mutations were dispatched
 * @param affectedIds     action IDs that were (or would be, if preview) toggled
 * @param skippedIds      action IDs excluded from the plan because their type is not rollbackable
 * @param plannedSteps    number of world mutations the plan would dispatch
 * @param dispatchedSteps number of world mutations actually submitted to the executor
 *                        ({@code 0} for previews)
 * @param originalFilter  the (rolledBack-normalized) filter the plan was built
 *                        from; may be {@code null} for legacy callers that
 *                        used the pre-v1.1.6 7-arg constructor
 */
public record RollbackResult(
    UUID actorUuid,
    Mode mode,
    boolean preview,
    List<Long> affectedIds,
    List<Long> skippedIds,
    int plannedSteps,
    int dispatchedSteps,
    QueryFilter originalFilter
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

    /**
     * Backwards-compatible constructor that omits {@link #originalFilter()}.
     * Kept for pre-v1.1.6 call sites (tests + cell code before Undo rewires).
     */
    public RollbackResult(UUID actorUuid,
                          Mode mode,
                          boolean preview,
                          List<Long> affectedIds,
                          List<Long> skippedIds,
                          int plannedSteps,
                          int dispatchedSteps) {
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps, null);
    }

    /** Total actions touched (rolled back or restored). */
    public int affectedCount() {
        return affectedIds.size();
    }

    /**
     * The inverse of {@link #mode()}: {@code ROLLBACK}→{@code RESTORE} and
     * vice versa. Useful when wiring {@code /vg undo} — pop an entry and
     * apply the inverse mode to {@link #originalFilter()}.
     */
    public Mode inverseMode() {
        return mode == Mode.ROLLBACK ? Mode.RESTORE : Mode.ROLLBACK;
    }
}
