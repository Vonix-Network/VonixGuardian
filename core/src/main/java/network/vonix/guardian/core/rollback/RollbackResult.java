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
 * <p>2.1.1: every execute path returns one authoritative {@link Status}. Mixed
 * world outcomes, compensation, repair-required, and persistence failure after
 * world success are never collapsed into a generic exception.</p>
 *
 * @param actorUuid            who triggered the operation; may be {@code null} for console
 * @param mode                 {@link Mode#ROLLBACK} or {@link Mode#RESTORE}
 * @param preview              {@code true} when no mutations were dispatched
 * @param affectedIds          action IDs that were (or would be, if preview) toggled
 * @param skippedIds           action IDs excluded from the plan or skipped at dispatch
 * @param plannedSteps         number of world mutations the plan would dispatch
 * @param dispatchedSteps      number of world mutations confirmed after executor completion
 *                             ({@code 0} for previews)
 * @param originalFilter       the (rolledBack-normalized) filter the plan was built
 *                             from; may be {@code null} for legacy callers that
 *                             used the pre-v1.1.6 7-arg constructor
 * @param status               authoritative terminal status for this request
 * @param batchId              {@code vg_rollback_batches.id}, or {@code 0} when no batch opened
 * @param failedCount          world mutations that failed (not skipped, not compensated)
 * @param compensatedCount     pair halves that were successfully compensated after mate failure
 * @param repairRequiredCount  pair halves persisted to {@code vg_repair_required}
 * @param batchClosed          {@code true} only when {@code closeRollbackBatch} updated exactly one row
 */
public record RollbackResult(
    UUID actorUuid,
    Mode mode,
    boolean preview,
    List<Long> affectedIds,
    List<Long> skippedIds,
    int plannedSteps,
    int dispatchedSteps,
    QueryFilter originalFilter,
    Status status,
    long batchId,
    int failedCount,
    int compensatedCount,
    int repairRequiredCount,
    boolean batchClosed
) {

    /** Operation kind. */
    public enum Mode { ROLLBACK, RESTORE }

    /**
     * Authoritative terminal status for one rollback/restore/undo request.
     * Command adapters must print this status; they must not invent a later
     * generic failure for the same request.
     */
    public enum Status {
        SUCCESS,
        PARTIAL,
        FAILED,
        COMPENSATED,
        REPAIR_REQUIRED
    }

    public RollbackResult {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        affectedIds = affectedIds == null ? List.of() : List.copyOf(affectedIds);
        skippedIds = skippedIds == null ? List.of() : List.copyOf(skippedIds);
        if (plannedSteps < 0) {
            throw new IllegalArgumentException("plannedSteps < 0");
        }
        if (dispatchedSteps < 0) {
            throw new IllegalArgumentException("dispatchedSteps < 0");
        }
        if (failedCount < 0 || compensatedCount < 0 || repairRequiredCount < 0) {
            throw new IllegalArgumentException("counts < 0");
        }
        if (batchId < 0L) {
            throw new IllegalArgumentException("batchId < 0");
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
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps,
            null, Status.SUCCESS, 0L, 0, 0, 0, true);
    }

    /**
     * Pre-2.1.1 constructor. Defaults status to {@link Status#SUCCESS} with a
     * closed batch and zero failure/compensation/repair counts.
     */
    public RollbackResult(UUID actorUuid,
                          Mode mode,
                          boolean preview,
                          List<Long> affectedIds,
                          List<Long> skippedIds,
                          int plannedSteps,
                          int dispatchedSteps,
                          QueryFilter originalFilter) {
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps,
            originalFilter, Status.SUCCESS, 0L, 0, 0, 0, true);
    }

    /** Total actions touched (rolled back or restored). */
    public int affectedCount() {
        return affectedIds.size();
    }

    /** Dispatch-time plus plan-time skips. */
    public int skippedCount() {
        return skippedIds.size();
    }

    /** Applied world mutations that were marked in the DAO. */
    public int appliedCount() {
        return affectedIds.size();
    }

    /**
     * {@code true} only for a fully successful (or successful preview/no-op) request.
     * PARTIAL / FAILED / COMPENSATED / REPAIR_REQUIRED are not success.
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * World state may have changed. Undo history should retain these results.
     */
    public boolean mutatedWorld() {
        return !preview && (appliedCount() > 0 || compensatedCount > 0 || repairRequiredCount > 0);
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
