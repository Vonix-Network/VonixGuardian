package network.vonix.guardian.core.rollback;

import java.util.Objects;

/**
 * Completion outcome for one planned world mutation.
 *
 * <p>{@link Status#APPLIED} is reported only after the mutator call returned
 * on the server-thread executor. Enqueueing the task is not an applied
 * outcome.</p>
 */
public record WorldMutationResult(long actionId, Status status, Throwable failure, Long pairId) {

    public enum Status { APPLIED, SKIPPED, FAILED, REPAIR_REQUIRED }

    /** Source-compatible constructor for non-paired outcomes. */
    public WorldMutationResult(long actionId, Status status, Throwable failure) {
        this(actionId, status, failure, null);
    }

    public WorldMutationResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.FAILED || status == Status.REPAIR_REQUIRED) && failure == null) {
            throw new IllegalArgumentException(status + " requires failure");
        }
        if (status != Status.FAILED && status != Status.REPAIR_REQUIRED && failure != null) {
            throw new IllegalArgumentException("Only FAILED or REPAIR_REQUIRED may carry failure");
        }
        if (pairId != null && pairId == 0L) {
            throw new IllegalArgumentException("pairId must be non-zero when present");
        }
    }

    public static WorldMutationResult applied(long actionId) {
        return new WorldMutationResult(actionId, Status.APPLIED, null);
    }

    public static WorldMutationResult skipped(long actionId) {
        return new WorldMutationResult(actionId, Status.SKIPPED, null);
    }

    public static WorldMutationResult failed(long actionId, Throwable failure) {
        return new WorldMutationResult(actionId, Status.FAILED,
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
