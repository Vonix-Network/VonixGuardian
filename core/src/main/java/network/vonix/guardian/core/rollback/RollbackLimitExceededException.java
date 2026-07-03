package network.vonix.guardian.core.rollback;

/** Thrown when rollback planning exceeds an operator safety cap. */
public final class RollbackLimitExceededException extends Exception {
    private final RollbackProgress progress;

    public RollbackLimitExceededException(String message, RollbackProgress progress) {
        super(message);
        this.progress = progress;
    }

    public RollbackProgress progress() {
        return progress;
    }
}
