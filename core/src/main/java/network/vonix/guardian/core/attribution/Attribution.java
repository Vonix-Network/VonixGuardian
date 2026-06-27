/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of "who is responsible for this entity-driven action."
 *
 * <p>Produced by the loader-side
 * {@link network.vonix.guardian.core.attribution.AttributionResolver} when an
 * entity (vanilla or modded) causes a logged action. Stored on
 * {@link network.vonix.guardian.core.action.Action#meta()} as JSON:
 *
 * <pre>{@code
 * {"actor":"069a79f4-...","name":"Notch","kind":"PLAYER_RIDER",
 *  "entity":"#mob:isleofberk:skrill","hops":1}
 * }</pre>
 *
 * @param actorUuid       resolved player UUID, or {@code null} for natural / unknown
 * @param actorName       resolved player name; for natural sources, the entity sentinel
 *                        (e.g. {@code "#mob:cataclysm:netherite_monstrosity"})
 * @param kind            classification of how attribution was decided
 * @param entitySentinel  the entity that performed the action, always recorded
 *                        as {@code "#mob:<namespace>:<path>"} regardless of attribution kind
 * @param chainHops       attribution chain depth: 0 = direct/rider/owner;
 *                        1 = projectile or damage-history; 2 = NBT scan
 */
public record Attribution(
        UUID actorUuid,
        String actorName,
        AttributionKind kind,
        String entitySentinel,
        int chainHops
) {

    public Attribution {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entitySentinel, "entitySentinel");
        if (chainHops < 0) {
            throw new IllegalArgumentException("chainHops must be >= 0");
        }
        if (kind.isPlayer() && actorUuid == null) {
            throw new IllegalArgumentException("player kind requires actorUuid: " + kind);
        }
        if (actorName == null) {
            // for natural kinds, default name to the entity sentinel
            actorName = entitySentinel;
        }
    }

    /** Build a direct-player attribution (player took the action themselves). */
    public static Attribution direct(UUID player, String playerName, String entitySentinel) {
        return new Attribution(player, playerName, AttributionKind.PLAYER_DIRECT, entitySentinel, 0);
    }

    /** Build a rider attribution (player was controlling passenger of the entity). */
    public static Attribution rider(UUID player, String playerName, String entitySentinel) {
        return new Attribution(player, playerName, AttributionKind.PLAYER_RIDER, entitySentinel, 0);
    }

    /** Build an owner attribution (resolved via TamableAnimal / OwnableEntity interface). */
    public static Attribution owner(UUID player, String playerName, String entitySentinel) {
        return new Attribution(player, playerName, AttributionKind.PLAYER_OWNER, entitySentinel, 0);
    }

    /** Build a tamer attribution (resolved via NBT scan rather than interface). */
    public static Attribution tamer(UUID player, String playerName, String entitySentinel, int hops) {
        return new Attribution(player, playerName, AttributionKind.PLAYER_TAMER, entitySentinel, hops);
    }

    /** Build a projectile attribution (recurses through {@code projectile.getOwner()}). */
    public static Attribution projectile(UUID player, String playerName, String entitySentinel, int hops) {
        return new Attribution(player, playerName, AttributionKind.PLAYER_PROJECTILE, entitySentinel, hops);
    }

    /** Build an indirect-damage attribution (player hit the entity in the recent window). */
    public static Attribution indirect(UUID player, String playerName, String entitySentinel) {
        return new Attribution(player, playerName, AttributionKind.PLAYER_INDIRECT, entitySentinel, 1);
    }

    /** Build a natural attribution with a specific sub-classification. */
    public static Attribution natural(AttributionKind naturalKind, String entitySentinel) {
        if (!naturalKind.isNatural()) {
            throw new IllegalArgumentException("not a natural kind: " + naturalKind);
        }
        return new Attribution(null, entitySentinel, naturalKind, entitySentinel, 0);
    }

    /** Build an UNKNOWN attribution — used when the resolver cannot decide. */
    public static Attribution unknown(String entitySentinel) {
        return new Attribution(null, entitySentinel, AttributionKind.UNKNOWN, entitySentinel, 0);
    }
}
