package network.vonix.guardian.core.rollback;

import java.util.List;
import java.util.UUID;

/**
 * Loader-provided contract for mutating the world during a rollback or restore.
 *
 * <h2>What {@code true} means</h2>
 * A {@code true} return from any {@code try*} method is a claim about the
 * world, not about the call: <em>this implementation performed the complete
 * requested mutation</em>. It must not be returned when part of the request
 * could not be applied. Runtime read-back is still a separate step; {@code
 * true} means the mutation API reported complete success.
 *
 * <p>A {@code false} return means the world was left as it was found —
 * either nothing was attempted, or the attempt was compensated. An
 * implementation that mutated the world and then discovered it could not
 * complete must restore the pre-state before answering {@code false}, or
 * throw. It must never answer {@code false} after leaving a partial mutation
 * in place: the engine reads {@code false} as "this row did not apply" and
 * will report accounting that the operator cannot reconcile.
 *
 * <p>Concrete implementations must return {@code false} for missing worlds,
 * unknown registry ids, absent containers/entities, failed Minecraft mutation
 * calls, NBT decode/apply failures, and partial container mutations.
 *
 * <h2>Compatibility defaults are inert</h2>
 * The original {@code void} descriptors are retained as the stable public
 * ABI for integrations built against pre-1.3.10 releases. They remain
 * {@code no-op} defaults, so the interface keeps evolving without breaking
 * third-party implementations.
 *
 * <p>The checked {@code try*} methods, however, have <strong>no
 * mutating default</strong>. An implementation that only provides the {@code
 * void} methods cannot supply checked success evidence, so every checked
 * default answers {@code false} <em>without touching the world</em>. This is
 * deliberate: a default that forwarded to {@code setBlock(...)} and then
 * returned {@code false} would mutate the world and simultaneously report
 * failure — the worst of both outcomes, leaving a change the operator can
 * neither see in the applied count nor reverse by re-running.
 *
 * <p>Loader implementations override the checked methods. A checked overload
 * that an implementation does not override reports "not supported", and the
 * engine fails that row closed rather than assuming the legacy void path did
 * the right thing.
 *
 * <p>All methods are invoked on the server thread.
 */
public interface WorldMutator {

    /** Stable legacy ABI: set a block. Inert default; checked loader paths override trySetBlock. */
    default void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /** Stable legacy ABI: NBT-aware block set. Inert default; checked loader paths override trySetBlock. */
    default void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                          String blockState, byte[] blockEntityNbt) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /**
     * Checked block mutation path used by RollbackEngine.
     *
     * <p>Default is unsupported: it does not mutate and returns {@code false}.
     */
    default boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
        return false;
    }

    /**
     * Checked NBT-aware block mutation path used by RollbackEngine.
     *
     * <p>Default is unsupported: it does not mutate and returns {@code false}.
     * It deliberately does not fall through to {@link #trySetBlock(String, int,
     * int, int, String, String)} — dropping a block-entity payload because an
     * implementation forgot the overload would silently restore a container as
     * an empty one, which is worse than an explicit "not applied".
     */
    default boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                                String blockState, byte[] blockEntityNbt) {
        return false;
    }

    /** Stable legacy ABI: insert a stack or drop it. Inert default; checked loader paths override tryGiveOrDrop. */
    default void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /** Stable legacy ABI: NBT-aware insert/drop. Inert default; checked loader paths override tryGiveOrDrop. */
    default void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                            String targetMeta, byte[] itemNbt) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /** Checked insert/drop path used by RollbackEngine. Default is unsupported: no mutation, {@code false}. */
    default boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                                  String targetMeta) {
        return false;
    }

    /**
     * Checked NBT-aware insert/drop path used by RollbackEngine.
     *
     * <p>Default is unsupported: no mutation, {@code false}. It does not fall
     * through to the NBT-less overload, because that would apply the item with
     * its enchantments and contents stripped and still answer {@code true}.
     */
    default boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                                  String targetMeta, byte[] itemNbt) {
        return false;
    }

    /**
     * Checked clear-to-air path used by RollbackEngine for every inverse that
     * removes a block.
     *
     * <p>v2.2.0 OE1: {@code trySetBlock(..., AIR, null)} alone is not sufficient
     * to clear a position. Vanilla {@code Level.setBlock} removes the block
     * entity only as a side effect of a {@code BlockEntity}-owning block state
     * change; a stale block entity at a position whose new state does not own
     * one (gravel, fluid, kelp, air, a rotated pillar) can survive the write and
     * then be serialised back to disk. On the next chunk load
     * {@code BlockEntity.loadStatic} reconstructs the recorded id
     * ({@code minecraft:bed} / {@code minecraft:banner}) against the mismatched
     * block state and throws {@code ClassCastException}, which aborts the block
     * entity and spams the console per affected position.
     *
     * <p>Implementations MUST set the block to air first, while any genuine
     * block entity is still attached, so vanilla's own removal path runs.
     * They then perform a non-creating read and explicitly remove a survivor.
     * Return {@code true} only when the final state is air and that read is
     * null. Never call an entity-creating block-entity accessor between the
     * removal and the state write: that lookup recreates an empty entity, the
     * clear aborts, and the container contents are already gone. If the air
     * write does not land, do not remove the block entity. Report {@code false}
     * when the world is unavailable, a survivor cannot be removed, or the
     * final state is not air. Reporting {@code true} on a partially-applied
     * clear reintroduces the orphaned-block-entity defect.
     *
     * <p>The default is unsupported: it does not mutate and returns {@code
     * false}, so the rollback reports {@code REPAIR_REQUIRED} rather than
     * silently leaving corruption behind.
     */
    default boolean tryClearBlock(String worldId, int x, int y, int z) {
        return false;
    }

    /** Stable legacy ABI: remove a quantity from a container. Inert default; checked loader paths override tryRemoveFromContainer. */
    default void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /** Checked container-removal path used by RollbackEngine. Default is unsupported: no mutation, {@code false}. */
    default boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
        return false;
    }

    /**
     * Slot-aware container removal. {@code inventorySlot == null} falls back to
     * the historical scan-all path so 2.0.1 rows keep compiling. A non-null
     * slot that this implementation cannot honour must return {@code false}
     * (fail closed) rather than dropping items or scanning other slots.
     *
     * <p>The scan-all fallback is the one intentional delegation left in the
     * checked surface: {@link #tryRemoveFromContainer(String, int, int, int,
     * String, int)} has no mutating default, so falling back to it either
     * reaches a real implementation or answers {@code false} without touching
     * the world.
     */
    default boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                           String targetMeta, byte[] itemNbt, Integer inventorySlot) {
        if (inventorySlot != null) {
            return false;
        }
        return tryRemoveFromContainer(worldId, x, y, z, itemId, amount);
    }

    /**
     * Slot-aware container insertion. Must not drop leftovers on the ground.
     * A non-null slot that cannot take the complete stack returns {@code false}.
     *
     * <p>{@code inventorySlot == null} means "this row carries no slot", and
     * the historical behaviour is to restore the stack into the container or
     * the world. The default therefore uses {@link #tryGiveOrDrop(String, int,
     * int, int, String, int, String, byte[])} — never the plain overload, which
     * would drop the NBT payload — and that method's default is unsupported, so
     * an implementation that overrides neither still reports {@code false}
     * without mutating.
     */
    default boolean tryAddToContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                      String targetMeta, byte[] itemNbt, Integer inventorySlot) {
        if (inventorySlot != null) {
            return false;
        }
        return tryGiveOrDrop(worldId, x, y, z, itemId, amount, targetMeta, itemNbt);
    }

    /**
     * Legacy slot-less player-inventory insertion retained for existing
     * mutator implementations. Default is unsupported: no mutation,
     * {@code false}.
     */
    default boolean tryAddToPlayerInventory(UUID playerUuid, String itemId, int amount,
                                             String targetMeta, byte[] itemNbt) {
        return false;
    }

    /**
     * Checked insertion into the named player's inventory. The actor UUID is
     * the integrity boundary; implementations must fail closed when the player
     * is not online or the complete stack cannot be inserted.
     *
     * <p>Default is unsupported: no mutation, {@code false}. It does not
     * delegate to the slot-less overload — an unknown target slot is not the
     * same request as "any slot", and silently widening it would restore stack
     * contents to a position the operator did not ask for.
     */
    default boolean tryAddToPlayerInventory(UUID playerUuid, String itemId, int amount,
                                             String targetMeta, byte[] itemNbt,
                                             Integer inventorySlot) {
        return false;
    }

    /**
     * Legacy slot-less player-inventory removal retained for existing mutator
     * implementations. Default is unsupported: no mutation, {@code false}.
     */
    default boolean tryRemoveFromPlayerInventory(UUID playerUuid, String itemId, int amount,
                                                  String targetMeta, byte[] itemNbt) {
        return false;
    }

    /**
     * Checked removal from the named player's inventory. Implementations must
     * match item metadata/components, not merely the registry id, and must not
     * report success after a partial removal.
     *
     * <p>Default is unsupported: no mutation, {@code false} (see {@link
     * #tryAddToPlayerInventory(UUID, String, int, String, byte[], Integer)} for
     * why it does not widen to the slot-less overload).
     */
    default boolean tryRemoveFromPlayerInventory(UUID playerUuid, String itemId, int amount,
                                                String targetMeta, byte[] itemNbt,
                                                Integer inventorySlot) {
        return false;
    }

    /** Stable legacy ABI: respawn an entity. Inert default; checked loader paths override tryRespawnEntity. */
    default void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /** Stable legacy ABI: NBT-aware entity respawn. Inert default; checked loader paths override tryRespawnEntity. */
    default void respawnEntity(String worldId, int x, int y, int z, String entityType,
                               String targetMeta, byte[] entityNbt) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /** Checked entity-respawn path used by RollbackEngine. Default is unsupported: no mutation, {@code false}. */
    default boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType,
                                     String targetMeta) {
        return false;
    }

    /**
     * Checked NBT-aware entity-respawn path used by RollbackEngine.
     *
     * <p>Default is unsupported: no mutation, {@code false}. It does not fall
     * through to the plain overload — respawning a killed entity without its
     * NBT produces a different entity (wrong variant, empty inventory, no
     * name) that still answers {@code true}.
     */
    default boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType,
                                     String targetMeta, byte[] entityNbt) {
        return false;
    }

    /** Stable legacy ABI: remove a matching hanging entity. Inert default; checked loader paths override tryRemoveEntity. */
    default void removeEntity(String worldId, int x, int y, int z, String entityType) {
        // Stable ABI compatibility default. Not a mutation, not success evidence.
    }

    /**
     * Checked entity-removal path used by RollbackEngine.
     *
     * <p>The recorded position and entity type are a <em>filter</em>, not an
     * identity. Entities move between the moment the row was written and the
     * moment it is reversed: dropped items drift and fall, and a hanging entity
     * perturbed by an explosion or a piston settles on a neighbouring block. An
     * implementation that requires the entity to still occupy the exact
     * recorded block silently fails every drifted row.
     *
     * <p>Implementations therefore:
     * <ol>
     *   <li>match the entity type <strong>strictly</strong> — the registry id
     *       must equal the requested id, never a superclass or tag match;</li>
     *   <li>gather same-type candidates in a box inflated by
     *       {@link #REMOVE_ENTITY_MAX_DRIFT_BLOCKS};</li>
     *   <li>choose one with {@link #selectNearestEntityCandidate}, which prefers
     *       an exact block match and then the nearest drifted candidate;</li>
     *   <li>remove at most <strong>one</strong> entity and return {@code true},
     *       or return {@code false} having removed nothing.</li>
     * </ol>
     *
     * <p>The bounded radius exists because the engine cannot verify entity
     * identity: every entity of the same type inside the radius is a candidate.
     * The bound keeps that ambiguity as small as it can be while still
     * covering ordinary item drift.
     *
     * <p>Default is unsupported: no mutation, {@code false}.
     */
    default boolean tryRemoveEntity(String worldId, int x, int y, int z, String entityType) {
        return false;
    }

    /**
     * Maximum drift, in blocks, tolerated on each axis when matching the entity
     * a row recorded. A dropped item falls under gravity and slides; the
     * default covers a fall of a few blocks plus horizontal drift.
     */
    int REMOVE_ENTITY_MAX_DRIFT_BLOCKS = 3;

    /**
     * A same-type entity that {@link #tryRemoveEntity} may be allowed to remove.
     *
     * @param blockX           block X of the entity's current {@code blockPosition()}
     * @param blockY           block Y of the entity's current {@code blockPosition()}
     * @param blockZ           block Z of the entity's current {@code blockPosition()}
     * @param centerDistanceSq squared distance from the entity's precise position
     *                         to the centre of the recorded block, used to pick
     *                         between multiple drifted candidates
     */
    record EntityCandidate(int blockX, int blockY, int blockZ, double centerDistanceSq) {
    }

    /**
     * Chooses which candidate entity a removal may act on, or {@code -1} when
     * none is close enough.
     *
     * <p>Shared by every loader cell so the drift policy has exactly one
     * definition; it is pure arithmetic and therefore testable without a
     * Minecraft runtime.
     *
     * <p>Selection order:
     * <ol>
     *   <li>The first candidate occupying the recorded block wins outright. An
     *       exact match is precise evidence and must not be displaced by a
     *       different entity that happens to have a smaller centre distance —
     *       a neighbour's entity can be nearer to the recorded block's centre
     *       than one standing inside it.</li>
     *   <li>Otherwise the candidate with the smallest centre distance among
     *       those within {@code maxDrift} blocks on every axis. Ties resolve to
     *       the earliest candidate, so a re-run chooses the same entity given
     *       the same world.</li>
     * </ol>
     *
     * <p>Everything else — the type check, the query box, the {@code discard()}
     * — stays in the loader cell, because only the cell can express it.
     *
     * @return index into {@code candidates}, or {@code -1}
     */
    static int selectNearestEntityCandidate(int x, int y, int z, List<EntityCandidate> candidates, int maxDrift) {
        if (candidates == null || candidates.isEmpty() || maxDrift < 0) {
            return -1;
        }
        for (int i = 0; i < candidates.size(); i++) {
            EntityCandidate c = candidates.get(i);
            if (c == null) continue;
            if (c.blockX() == x && c.blockY() == y && c.blockZ() == z) {
                return i;
            }
        }
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            EntityCandidate c = candidates.get(i);
            if (c == null) continue;
            if (Math.abs(c.blockX() - x) > maxDrift
                    || Math.abs(c.blockY() - y) > maxDrift
                    || Math.abs(c.blockZ() - z) > maxDrift) {
                continue;
            }
            double distance = c.centerDistanceSq();
            // A candidate with no usable distance never wins; NaN would otherwise
            // make every comparison false and leave the first drifted candidate
            // silently unselected.
            if (!Double.isNaN(distance) && distance >= 0.0 && distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }
}
