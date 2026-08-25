package network.vonix.guardian.core.rollback;

import java.util.UUID;

/**
 * Loader-provided contract for mutating the world during a rollback or restore.
 *
 * <p>The original {@code void} descriptors are retained as the stable public
 * ABI. New rollback code calls the {@code try*} methods, which provide an
 * explicit success result. Legacy external implementations that only provide
 * the void methods are therefore loadable, but their checked path deliberately
 * returns {@code false}; it cannot provide checked success evidence.
 *
 * <p>All methods are invoked on the server thread. Real loader implementations
 * must return {@code false} for missing worlds, unknown registry ids, absent
 * containers/entities, failed Minecraft mutation calls, NBT decode/apply
 * failures, and partial container mutations. A {@code true} result is the
 * mutation API boundary only; runtime read-back remains a separate gap.</p>
 */
public interface WorldMutator {

    /** Stable legacy ABI: set a block. */
    default void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        // Compatibility default for new implementations; checked loader paths override trySetBlock.
    }

    /** Stable legacy ABI: NBT-aware block set. */
    default void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                          String blockState, byte[] blockEntityNbt) {
        setBlock(worldId, x, y, z, targetId, targetMeta);
    }

    /** Checked block mutation path used by RollbackEngine. */
    default boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        setBlock(worldId, x, y, z, targetId, targetMeta);
        return false;
    }

    /** Checked NBT-aware block mutation path used by RollbackEngine. */
    default boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                                String blockState, byte[] blockEntityNbt) {
        setBlock(worldId, x, y, z, targetId, targetMeta, blockState, blockEntityNbt);
        return false;
    }

    /** Stable legacy ABI: insert a stack or drop it. */
    default void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
        // Compatibility default for new implementations; checked loader paths override tryGiveOrDrop.
    }

    /** Stable legacy ABI: NBT-aware insert/drop. */
    default void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                            String targetMeta, byte[] itemNbt) {
        giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
    }

    /** Checked insert/drop path used by RollbackEngine. */
    default boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                                  String targetMeta) {
        giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta);
        return false;
    }

    /** Checked NBT-aware insert/drop path used by RollbackEngine. */
    default boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                                  String targetMeta, byte[] itemNbt) {
        giveOrDrop(worldId, x, y, z, itemId, amount, targetMeta, itemNbt);
        return false;
    }

    /** Stable legacy ABI: remove a quantity from a container. */
    default void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        // Compatibility default for new implementations; checked loader paths override tryRemoveFromContainer.
    }

    /** Checked container-removal path used by RollbackEngine. */
    default boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        removeFromContainer(worldId, x, y, z, itemId, amount);
        return false;
    }

    /** Legacy slot-less overload retained for existing mutator implementations. */
    default boolean tryAddToPlayerInventory(UUID playerUuid, String itemId, int amount,
                                             String targetMeta, byte[] itemNbt) {
        return false;
    }

    /**
     * Checked insertion into the named player's inventory. The actor UUID is
     * the integrity boundary; implementations must fail closed when the player
     * is not online or the complete stack cannot be inserted.
     */
    default boolean tryAddToPlayerInventory(UUID playerUuid, String itemId, int amount,
                                             String targetMeta, byte[] itemNbt,
                                             Integer inventorySlot) {
        return false;
    }

    /** Legacy slot-less overload retained for existing mutator implementations. */
    default boolean tryRemoveFromPlayerInventory(UUID playerUuid, String itemId, int amount,
                                                  String targetMeta, byte[] itemNbt) {
        return false;
    }

    /**
     * Checked removal from the named player's inventory. Implementations must
     * match item metadata/components, not merely the registry id, and must not
     * report success after a partial removal.
     */
    default boolean tryRemoveFromPlayerInventory(UUID playerUuid, String itemId, int amount,
                                                String targetMeta, byte[] itemNbt,
                                                Integer inventorySlot) {
        return false;
    }

    /** Stable legacy ABI: respawn an entity. */
    default void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
        // Compatibility default for new implementations; checked loader paths override tryRespawnEntity.
    }

    /** Stable legacy ABI: NBT-aware entity respawn. */
    default void respawnEntity(String worldId, int x, int y, int z, String entityType,
                               String targetMeta, byte[] entityNbt) {
        respawnEntity(worldId, x, y, z, entityType, targetMeta);
    }

    /** Checked entity-respawn path used by RollbackEngine. */
    default boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType,
                                     String targetMeta) {
        respawnEntity(worldId, x, y, z, entityType, targetMeta);
        return false;
    }

    /** Checked NBT-aware entity-respawn path used by RollbackEngine. */
    default boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType,
                                     String targetMeta, byte[] entityNbt) {
        respawnEntity(worldId, x, y, z, entityType, targetMeta, entityNbt);
        return false;
    }

    /** Stable legacy ABI: remove a matching hanging entity. */
    default void removeEntity(String worldId, int x, int y, int z, String entityType) {
        // Compatibility default; checked loader paths override tryRemoveEntity.
    }

    /** Checked hanging-entity removal path used by RollbackEngine. */
    default boolean tryRemoveEntity(String worldId, int x, int y, int z, String entityType) {
        removeEntity(worldId, x, y, z, entityType);
        return false;
    }
}
