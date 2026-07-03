package network.vonix.guardian.core.rollback;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Safety controls for rollback/restore planning.
 *
 * <p>Defaults intentionally cap scan and plan size so a broad command cannot
 * materialize an unbounded result set in memory on a busy modded server. Callers
 * that really want larger jobs should ask the operator for a narrower filter or
 * explicitly pass larger limits.</p>
 */
public record RollbackOptions(
    int pageSize,
    int maxScannedActions,
    int maxPlannedSteps,
    BooleanSupplier cancelRequested,
    Consumer<RollbackProgress> progressListener
) {
    public static final int DEFAULT_PAGE_SIZE = RollbackEngine.PAGE_SIZE;
    public static final int DEFAULT_MAX_SCANNED_ACTIONS = 100_000;
    public static final int DEFAULT_MAX_PLANNED_STEPS = 50_000;

    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final Consumer<RollbackProgress> NOOP_PROGRESS = ignored -> { };

    public RollbackOptions {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be > 0");
        if (maxScannedActions <= 0) throw new IllegalArgumentException("maxScannedActions must be > 0");
        if (maxPlannedSteps <= 0) throw new IllegalArgumentException("maxPlannedSteps must be > 0");
        cancelRequested = cancelRequested == null ? NEVER_CANCELLED : cancelRequested;
        progressListener = progressListener == null ? NOOP_PROGRESS : progressListener;
    }

    public static RollbackOptions defaults() {
        return new RollbackOptions(DEFAULT_PAGE_SIZE, DEFAULT_MAX_SCANNED_ACTIONS,
            DEFAULT_MAX_PLANNED_STEPS, NEVER_CANCELLED, NOOP_PROGRESS);
    }

    static RollbackOptions normalize(RollbackOptions options) {
        return options == null ? defaults() : options;
    }

    boolean isCancelRequested() {
        return cancelRequested.getAsBoolean();
    }

    void publish(RollbackProgress progress) {
        progressListener.accept(Objects.requireNonNull(progress, "progress"));
    }
}
