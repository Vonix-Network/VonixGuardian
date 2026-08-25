package network.vonix.guardian.core.rollback;

/**
 * Exact-slot mutation succeeded, {@code setChanged()} (or an equivalent
 * follow-up) failed, and restoring the previous stack also failed or did not
 * read back. The world may remain half-mutated; callers must surface
 * {@link WorldMutationResult.Status#REPAIR_REQUIRED} rather than returning
 * a compensated {@code false}.
 */
public final class UncompensatedSlotMutationException extends RuntimeException {

    private final int slot;

    public UncompensatedSlotMutationException(int slot, Throwable mutateFailure, Throwable restoreFailure) {
        super("Uncompensated exact-slot mutation at slot=" + slot
                + "; world may remain mutated", mutateFailure);
        this.slot = slot;
        if (restoreFailure != null) {
            addSuppressed(restoreFailure);
        }
    }

    public int slot() {
        return slot;
    }
}
