/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.spi;

import java.util.UUID;

/**
 * Immutable description of one block position's auditable state.
 *
 * <p>{@code blockId} is the Hytale {@code BlockType} id (registry key, e.g.
 * {@code "Rock_Stone"}); {@code rotation} is the serialized rotation tuple
 * ({@code RotationTuple.toString()} or its codec form) so a rollback restores
 * the exact orientation; {@code stateJson} carries any additional per-position
 * state the port needs as JSON text; {@code componentData} is the opaque
 * component payload (serialized block-entity/meta data) when the block owns one.
 *
 * <p>Records are JSON-serializable by construction; do not add non-serializable
 * fields.
 */
public record BlockSnapshot(
    String blockId,
    String rotation,
    String stateJson,
    byte[] componentData
) {

    public static final BlockSnapshot AIR =
        new BlockSnapshot("Air", null, null, null);

    public BlockSnapshot {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId must not be blank");
        }
    }

    public boolean isAir() {
        return "Air".equals(blockId) || "Empty".equals(blockId);
    }

    public boolean hasComponentData() {
        return componentData != null && componentData.length > 0;
    }
}
