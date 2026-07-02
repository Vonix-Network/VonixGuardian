/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#inventoryLookup} call — a single
 * player-inventory transaction (INVENTORY_DEPOSIT / INVENTORY_WITHDRAW).
 *
 * <p>Distinct from {@link ContainerLookupResult} in that these transactions
 * describe changes to the <b>player's own inventory</b> rather than to a
 * world container. Positional data is present but coarse (player location at
 * transaction time).
 *
 * @param time       epoch millis (UTC) when the event occurred
 * @param actorUuid  player UUID (never {@code null} — player-driven)
 * @param actorName  resolved name at event time
 * @param worldId    world the player was in
 * @param itemId     item registry id (e.g. {@code "minecraft:iron_ingot"})
 * @param meta       compact-JSON item NBT snapshot or {@code null}
 * @param amount     stack size affected (always {@code >= 0})
 * @param action     {@code "inventory"} — the lowercase token of the
 *                   underlying {@link network.vonix.guardian.core.action.ActionType#token()}
 *                   with any sign prefix stripped
 * @param rolledBack whether this event has been undone by a rollback
 * @param sourceTag  optional source classifier or {@code null}
 */
public record InventoryLookupResult(
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
