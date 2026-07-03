package network.vonix.guardian.core.rollback;

/**
 * Incremental planning progress for large rollback/restore operations.
 *
 * <p>The engine reports this after each DAO page and immediately before
 * aborting for cancel/cap conditions. It is intentionally compact so command
 * layers can turn it into chat/status output without retaining action rows.</p>
 */
public record RollbackProgress(
    int pagesFetched,
    int scannedActions,
    int plannedSteps,
    int skippedActions,
    boolean scanLimitReached,
    boolean plannedLimitReached,
    boolean cancelled
) {
    public RollbackProgress {
        if (pagesFetched < 0) throw new IllegalArgumentException("pagesFetched < 0");
        if (scannedActions < 0) throw new IllegalArgumentException("scannedActions < 0");
        if (plannedSteps < 0) throw new IllegalArgumentException("plannedSteps < 0");
        if (skippedActions < 0) throw new IllegalArgumentException("skippedActions < 0");
    }
}
