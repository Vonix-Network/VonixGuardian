package network.vonix.guardian.core.rollback;

import java.util.List;

/**
 * Indicates that one or more planned world mutations did not complete.
 * The rollback batch is deliberately left open so startup recovery can find
 * the affected action ids.
 */
public final class RollbackMutationException extends RuntimeException {

    private final long batchId;
    private final List<WorldMutationResult> outcomes;

    public RollbackMutationException(long batchId, List<WorldMutationResult> outcomes) {
        super("Rollback world mutation did not complete successfully",
            firstFailure(outcomes));
        this.batchId = batchId;
        this.outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }

    public long batchId() {
        return batchId;
    }

    public List<WorldMutationResult> outcomes() {
        return outcomes;
    }

    private static Throwable firstFailure(List<WorldMutationResult> outcomes) {
        if (outcomes != null) {
            for (WorldMutationResult outcome : outcomes) {
                if (outcome != null && outcome.failure() != null) {
                    return outcome.failure();
                }
            }
        }
        return null;
    }
}
