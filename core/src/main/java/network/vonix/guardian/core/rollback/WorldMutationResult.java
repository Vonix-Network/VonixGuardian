package network.vonix.guardian.core.rollback;

import java.util.Objects;

/**
 * Completion outcome for one planned world mutation.
 *
 * <p>{@link Status#APPLIED} is reported only after the mutator call returned
 * on the server-thread executor. Enqueueing the task is not an applied
 * outcome.</p>
 *
 * <p>A {@link Status#SKIPPED} outcome carries the {@link RollbackResult.SkipReason}
 * that explains it. Planning should resolve every knowable skip before dispatch;
 * a dispatch skip exists only for <em>newly discovered</em> facts, and it must
 * still be nameable or the operator's {@code skipped=N} cannot be reconciled.</p>
 */
public record WorldMutationResult(long actionId,
                                  Status status,
                                  Throwable failure,
                                  Long pairId,
                                  RollbackResult.SkipReason skipReason) {

    public enum Status { APPLIED, SKIPPED, FAILED, COMPENSATED, REPAIR_REQUIRED }

    public WorldMutationResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.FAILED || status == Status.REPAIR_REQUIRED || status == Status.COMPENSATED)
                && failure == null) {
            throw new IllegalArgumentException(status + " requires failure");
        }
        if (status != Status.FAILED && status != Status.REPAIR_REQUIRED && status != Status.COMPENSATED
                && failure != null) {
            throw new IllegalArgumentException("Only FAILED, COMPENSATED, or REPAIR_REQUIRED may carry failure");
        }
        if (pairId != null && pairId == 0L) {
            throw new IllegalArgumentException("pairId must be non-zero when present");
        }
        if (status != Status.SKIPPED && skipReason != null) {
            throw new IllegalArgumentException("Only SKIPPED may carry a skipReason");
        }
    }

    /** Source-compatible constructor for non-paired outcomes without a skip reason. */
    public WorldMutationResult(long actionId, Status status, Throwable failure) {
        this(actionId, status, failure, null, null);
    }

    /** Source-compatible constructor for paired outcomes without a skip reason. */
    public WorldMutationResult(long actionId, Status status, Throwable failure, Long pairId) {
        this(actionId, status, failure, pairId, null);
    }

    public static WorldMutationResult applied(long actionId) {
        return new WorldMutationResult(actionId, Status.APPLIED, null);
    }

    /**
     * The row was not attempted, for the named reason. Prefer resolving this at
     * plan time — a skip discovered at dispatch is still a defect in the plan,
     * even when it is honestly reported.
     */
    public static WorldMutationResult skipped(long actionId, RollbackResult.SkipReason reason) {
        return new WorldMutationResult(actionId, Status.SKIPPED, null, null,
            Objects.requireNonNull(reason, "reason"));
    }

    public static WorldMutationResult failed(long actionId, Throwable failure) {
        return new WorldMutationResult(actionId, Status.FAILED,
            Objects.requireNonNull(failure, "failure"));
    }

    /**
     * First half of a paired mutation was applied, then successfully inverted
     * after the mate failed. The world is not left half-mutated; the action is
     * not marked rolled-back.
     */
    public static WorldMutationResult compensated(long actionId, Throwable failure) {
        return new WorldMutationResult(actionId, Status.COMPENSATED,
            Objects.requireNonNull(failure, "failure"));
    }

    /**
     * World mutation remains applied (or unknown) after compensation failed.
     * Callers must persist this; it is not a log-only condition.
     */
    public static WorldMutationResult repairRequired(long actionId, Throwable failure) {
        return repairRequired(actionId, null, failure);
    }

    /**
     * World mutation remains applied (or unknown) after compensation failed,
     * retaining the durable inventory-pair correlation when available.
     */
    public static WorldMutationResult repairRequired(long actionId, Long pairId, Throwable failure) {
        return new WorldMutationResult(actionId, Status.REPAIR_REQUIRED,
            Objects.requireNonNull(failure, "failure"), pairId);
    }
}
