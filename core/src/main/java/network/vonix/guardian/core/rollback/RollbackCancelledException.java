package network.vonix.guardian.core.rollback;

/** Thrown when an operator/user cancels rollback planning before dispatch. */
public final class RollbackCancelledException extends Exception {
    private final RollbackProgress progress;

    public RollbackCancelledException(RollbackProgress progress) {
        super("Rollback planning cancelled after scanning "
            + (progress == null ? 0 : progress.scannedActions()) + " action(s)");
        this.progress = progress;
    }

    public RollbackProgress progress() {
        return progress;
    }
}
