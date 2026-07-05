/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#containerLookup} call — a single
 * historical container transaction (chest/hopper/inventory deposit / withdraw).
 *
 * <p>{@code amountDelta} carries the direction:
 * <ul>
 *   <li><b>positive</b> — deposited (items entered the container)</li>
 *   <li><b>negative</b> — withdrew (items left the container)</li>
 * </ul>
 *
 * @param time        epoch millis (UTC) when the event occurred
 * @param actorUuid   player UUID if known; {@code null} for hopper / dispense sources
 * @param actorName   resolved name or sentinel
 * @param worldId     world / dimension key
 * @param x           container X
 * @param y           container Y
 * @param z           container Z
 * @param itemId      item registry id (e.g. {@code "minecraft:diamond"})
 * @param targetMeta  compact-JSON NBT snapshot or {@code null}
 * @param amountDelta signed stack count — positive = deposit, negative = withdraw
 * @param rolledBack  whether this event has been undone by a rollback
 * @param sourceTag   optional source classifier or {@code null}
 */
public record ContainerLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String worldId,
        int x, int y, int z,
        String itemId,
        String targetMeta,
        int amountDelta,
        boolean rolledBack,
        String sourceTag,
        byte[] itemNbt
) {}
