package network.vonix.guardian.core.rollback;

/**
 * Order of block-entity removal for rollback clears and restores.
 *
 * <p>Vanilla {@code Level.getBlockEntity(pos)} uses
 * {@code EntityCreationType.IMMEDIATE}. On the server thread that recreates a
 * block entity whenever the block state still owns one. Calling it after
 * {@code removeBlockEntity} and before the air write therefore deletes the
 * real contents, observes the recreated empty entity, and aborts the clear
 * with the block still in place.
 *
 * <p>Loader cells adapt Minecraft calls into {@link ClearOps} / {@link PlaceOps}.
 * This class is the sequence those cells must run. It never reads a block
 * entity itself.
 */
public final class BlockEntityRestore {

    private BlockEntityRestore() {}

    /** Minecraft calls for {@link #clearToAir(ClearOps)}. */
    public interface ClearOps {
        /** Write air while any genuine block entity is still attached. */
        void setAir();

        boolean isAir();

        /** Non-creating presence check. Must not recreate a block entity. */
        boolean blockEntityPresent();

        void removeBlockEntity();
    }

    /**
     * Set air first. If that write does not land, leave the block entity
     * untouched and return false. Otherwise remove a survivor and return true
     * only when the position is air and a non-creating read is null.
     */
    public static boolean clearToAir(ClearOps ops) {
        ops.setAir();
        if (!ops.isAir()) {
            return false;
        }
        if (ops.blockEntityPresent()) {
            ops.removeBlockEntity();
        }
        return ops.isAir() && !ops.blockEntityPresent();
    }

    /** Minecraft calls for {@link #place(PlaceOps)}. */
    public interface PlaceOps {
        void setState();

        boolean stateMatches();

        boolean targetOwnsBlockEntity();

        /** Non-creating presence check. Must not recreate a block entity. */
        boolean blockEntityPresent();

        void removeBlockEntity();

        boolean hasNbt();

        /**
         * Apply supplied NBT to the block entity already present. Return false
         * when none is present or the apply fails. Must not create an entity.
         */
        boolean applyNbt();

        void restorePrevious();

        void onStaleClearFailed();
    }

    /**
     * After the state write, a non-creating read must be null when the target
     * owns no block entity. When the target owns one and no NBT was supplied,
     * the entity vanilla just created is left in place. Any failure restores
     * the previous state and returns false.
     */
    public static boolean place(PlaceOps ops) {
        ops.setState();
        if (!ops.stateMatches()) {
            ops.restorePrevious();
            return false;
        }
        if (!ops.targetOwnsBlockEntity()) {
            if (ops.blockEntityPresent()) {
                ops.removeBlockEntity();
            }
            if (ops.blockEntityPresent()) {
                ops.onStaleClearFailed();
                ops.restorePrevious();
                return false;
            }
            if (!ops.stateMatches()) {
                ops.restorePrevious();
                return false;
            }
            return true;
        }
        if (!ops.hasNbt()) {
            return ops.stateMatches();
        }
        if (!ops.applyNbt() || !ops.blockEntityPresent() || !ops.stateMatches()) {
            ops.restorePrevious();
            return false;
        }
        return true;
    }
}
