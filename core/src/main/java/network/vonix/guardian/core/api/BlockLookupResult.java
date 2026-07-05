/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#blockLookup} call — a single
 * historical block event at a specific coordinate.
 *
 * <p>Corresponds to CoreProtect's {@code net.coreprotect.model.BlockLookup}
 * shape. All fields are non-null except {@code targetMeta} (nullable JSON blob
 * capturing the block-state / NBT snapshot) and {@code sourceTag} (nullable
 * classifier like {@code "explosion:tnt"}).
 *
 * @param time       epoch millis (UTC) when the event occurred
 * @param actorUuid  player UUID if known; {@code null} for non-player sources
 *                   (creeper, TNT, lava, ...)
 * @param actorName  resolved name at event time, or sentinel ({@code "#creeper"} etc.)
 * @param worldId    world / dimension key (e.g. {@code "minecraft:overworld"})
 * @param x          block X
 * @param y          block Y
 * @param z          block Z
 * @param blockId    block registry id (e.g. {@code "minecraft:stone"})
 * @param targetMeta compact-JSON block-state / NBT blob or {@code null}
 * @param action     {@code "place"}, {@code "break"}, {@code "burn"},
 *                   {@code "explosion"}, etc. — the lowercase token of
 *                   the underlying {@link network.vonix.guardian.core.action.ActionType#token()}
 *                   with any sign prefix stripped
 * @param rolledBack whether this event has been undone by a rollback
 * @param sourceTag  optional source classifier or {@code null}
 */
public record BlockLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String worldId,
        int x, int y, int z,
        String blockId,
        String targetMeta,
        String action,
        boolean rolledBack,
        String sourceTag,
        String oldBlockState,
        String newBlockState,
        byte[] blockEntityNbt
) {}
