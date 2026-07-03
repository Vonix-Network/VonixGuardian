package network.vonix.guardian.core.rollback;

/**
 * Loader-provided contract for mutating the world during a rollback or restore.
 *
 * <p>All methods are invoked on the server thread (the
 * {@code mainThreadExecutor} passed to {@link RollbackEngine}). Implementations
 * must be defensive: positions may refer to unloaded chunks, removed entities,
 * or blocks of a different type than expected. Best-effort semantics apply —
 * mutations that cannot be performed should be silently no-ops, not throws.</p>
 */
public interface WorldMutator {

    /**
     * Set a block at {@code (worldId,x,y,z)} to {@code targetId}.
     *
     * @param worldId    world / dimension key (e.g. {@code "minecraft:overworld"})
     * @param x          block x
     * @param y          block y
     * @param z          block z
     * @param targetId   block registry id (e.g. {@code "minecraft:stone"}; {@code "minecraft:air"} to clear)
     * @param targetMeta optional NBT / block-state snapshot as compact JSON; may be {@code null}
     */
    void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta);

    /**
     * Insert a stack into the container at the given position. If no container
     * exists at that position, the stack is dropped on the ground.
     *
     * @param worldId    world / dimension key
     * @param x          block x
     * @param y          block y
     * @param z          block z
     * @param itemId     item registry id
     * @param amount     stack size; must be {@code >= 1}
     * @param targetMeta optional item NBT JSON; may be {@code null}
     */
    void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta);

    /**
     * Remove {@code amount} of {@code itemId} from the container at the given
     * position if present; no-op if not.
     *
     * @param worldId world / dimension key
     * @param x       block x
     * @param y       block y
     * @param z       block z
     * @param itemId  item registry id
     * @param amount  stack size to remove; must be {@code >= 1}
     */
    void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount);

    /**
     * Re-summon an entity at the given position (used to restore an
     * {@link network.vonix.guardian.core.action.ActionType#ENTITY_KILL}).
     *
     * @param worldId    world / dimension key
     * @param x          block x
     * @param y          block y
     * @param z          block z
     * @param entityType entity type registry id (e.g. {@code "minecraft:zombie"})
     * @param targetMeta optional entity NBT JSON; may be {@code null}
     */
    void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta);

    /**
     * Remove one matching entity near the given block position.
     *
     * <p>Used for rollback/restore of hanging entity place/break actions. The
     * default implementation is a no-op so older test doubles and external
     * integrations remain source-compatible; real loader cells override it.</p>
     *
     * @param worldId    world / dimension key
     * @param x          block x
     * @param y          block y
     * @param z          block z
     * @param entityType entity type registry id (e.g. {@code "minecraft:item_frame"})
     */
    default void removeEntity(String worldId, int x, int y, int z, String entityType) {
        // no-op by default
    }
}
