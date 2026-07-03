package network.vonix.guardian.core.rollback;

/**
 * Loader-provided contract for mutating the world during a rollback or restore.
 *
 * <p>All methods are invoked on the server thread (the
 * {@code mainThreadExecutor} passed to {@link RollbackEngine}). Implementations
 * must be defensive: positions may refer to unloaded chunks, removed entities,
 * or blocks of a different type than expected. Best-effort semantics apply —
 * mutations that cannot be performed should be silently no-ops, not throws.</p>
 *
 * <h2>v1.3.1 X1 — NBT-aware overloads</h2>
 *
 * <p>The rollback engine calls the NBT-aware overloads with the raw byte[]
 * captured by v5-schema producers when {@code storage.persistNbt=true}. Existing
 * implementations that only override the non-NBT methods keep working —
 * the default NBT overloads delegate to the non-NBT overload, discarding the
 * NBT payload. Loader cells that opt in to Ledger-parity restore fidelity
 * override the NBT overloads instead.</p>
 *
 * <p>NBT format: raw bytes produced by
 * {@code net.minecraft.nbt.NbtIo.write(CompoundTag, DataOutputStream)}; decode
 * on rollback via {@code NbtIo.read(...)}. On failure implementations should
 * log at DEBUG and fall back to the existing default-state behavior — never
 * throw, never leave the world in a partial state.</p>
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
     * v1.3.1 X1 NBT-aware overload for {@link #setBlock(String, int, int, int, String, String)}.
     *
     * @param worldId         world / dimension key
     * @param x               block x
     * @param y               block y
     * @param z               block z
     * @param targetId        block registry id
     * @param targetMeta      optional legacy compact-JSON meta; may be {@code null}
     * @param blockState      v5 block-state property string (e.g.
     *                        {@code "facing=north,waterlogged=true"}) captured by
     *                        producers when {@code storage.persistNbt=true}; may be
     *                        {@code null}
     * @param blockEntityNbt  raw block-entity NBT bytes (chest contents, spawner
     *                        data, sign back text, brewing stand fuel, etc.); may
     *                        be {@code null}
     * @since 1.3.1
     */
    default void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                          String blockState, byte[] blockEntityNbt) {
        setBlock(worldId, x, y, z, targetId, targetMeta);
    }

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
     * v1.3.1 X1 NBT-aware overload for
     * {@link #giveOrDrop(String, int, int, int, String, int, String)}.
     *
     * @param itemNbt raw item NBT bytes (nullable); reconstruct with
     *                {@code NbtIo.read(...)} to restore names, enchants, damage,
     *                attribute mods, etc.
     * @since 1.3.1
     */
    default void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                            String targetMeta, byte[] itemNbt) {
        giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
    }

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
     * v1.3.1 X1 NBT-aware overload for
     * {@link #respawnEntity(String, int, int, int, String, String)}.
     *
     * @param entityNbt raw entity NBT bytes (nullable); reconstruct with
     *                  {@code NbtIo.read(...)} to restore custom names, potion
     *                  effects, tame owner, equipment, etc.
     * @since 1.3.1
     */
    default void respawnEntity(String worldId, int x, int y, int z, String entityType,
                               String targetMeta, byte[] entityNbt) {
        respawnEntity(worldId, x, y, z, entityType, targetMeta);
    }

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
