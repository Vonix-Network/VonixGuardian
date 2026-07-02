/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#itemLookup} call — a single
 * item-family event (drop, pickup, craft).
 *
 * <p>Mirrors CoreProtect's item-lookup shape. All fields are non-null except
 * {@code actorUuid}, {@code meta} and {@code sourceTag} (nullable).
 *
 * @param time       epoch millis (UTC) when the event occurred
 * @param actorUuid  player UUID if known; {@code null} for non-player sources
 * @param actorName  resolved name at event time
 * @param worldId    world / dimension key (e.g. {@code "minecraft:overworld"})
 * @param itemId     item registry id (e.g. {@code "minecraft:diamond"})
 * @param meta       compact-JSON item NBT snapshot or {@code null}
 * @param amount     stack size affected (always {@code >= 0})
 * @param action     lowercase token of the underlying
 *                   {@link network.vonix.guardian.core.action.ActionType#token()}
 *                   with any sign prefix stripped (e.g. {@code "item"}, {@code "craft"})
 * @param rolledBack whether this event has been undone by a rollback
 * @param sourceTag  optional source classifier (e.g. {@code "drop:death"}) or {@code null}
 */
public record ItemLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String worldId,
        String itemId,
        String meta,
        int amount,
        String action,
        boolean rolledBack,
        String sourceTag
) {}
