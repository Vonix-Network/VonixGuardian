/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.spi;

import java.util.List;
import java.util.UUID;

/**
 * Read/write access to Hytale world blocks, expressed in the engine's string
 * vocabulary so {@code core/} never sees a Hytale type.
 *
 * <p>Contract:
 * <ul>
 *   <li>Every method must be invoked on the owning world's server thread
 *       ({@code World#execute}).</li>
 *   <li>{@link #read} returns {@code null} when the position is not loaded or
 *       the world id is unknown — never a fabricated AIR snapshot.</li>
 *   <li>{@link #write} returns {@code true} only after the mutation call was
 *       accepted <em>and</em> a read-back at the same position matches the
 *       requested {@code blockId}. A {@code true} here is the mutation API
 *       boundary, not proof the change was persisted to disk.</li>
 *   <li>Clearing a position must remove any block component data that the new
 *       block does not own (the Minecraft lanes' {@code tryClearToAir} lesson).</li>
 * </ul>
 */
public interface WorldBlockAccess {

    /** World ids as the engine uses them (stable across restarts). */
    List<String> worldIds();

    /** Stable engine world id for a live world, or {@code null} if unknown. */
    String worldIdOf(UUID worldUuid);

    /** Resolve an engine world id to the live world UUID, or {@code null}. */
    UUID worldUuidOf(String worldId);

    /** {@code true} when the chunk containing (x,z) is loaded in that world. */
    boolean isChunkLoaded(String worldId, int x, int z);

    /** Current state, or {@code null} when unavailable (unloaded world/chunk). */
    BlockSnapshot read(String worldId, int x, int y, int z);

    /** Apply a snapshot and verify it by read-back. Must be server-thread safe. */
    boolean write(String worldId, int x, int y, int z, BlockSnapshot snapshot);

    /** Clear to air, removing block component data. Verified by read-back. */
    default boolean clearToAir(String worldId, int x, int y, int z) {
        return write(worldId, x, y, z, BlockSnapshot.AIR);
    }
}
