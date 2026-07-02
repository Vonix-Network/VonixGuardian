/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#sessionLookup} call — a single
 * player session join or leave event.
 *
 * <p>Non-positional: {@code worldId} is where the player was at the moment of
 * the event; {@code x/y/z} are conventionally zero and are not included here.
 *
 * @param time       epoch millis (UTC) when the event occurred
 * @param actorUuid  player UUID (never {@code null})
 * @param actorName  resolved name at event time
 * @param worldId    world / dimension the player was in
 * @param action     {@code "session"} — lowercase token of the underlying
 *                   {@link network.vonix.guardian.core.action.ActionType#token()}
 *                   with any sign prefix stripped ({@code "session"})
 * @param direction  {@code "join"} for {@code SESSION_JOIN},
 *                   {@code "leave"} for {@code SESSION_LEAVE}
 * @param targetMeta implementation-specific detail — the IP (or hashed IP) on
 *                   join, or the reason string on leave. May be empty, never
 *                   {@code null}.
 */
public record SessionLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String worldId,
        String action,
        String direction,
        String targetMeta
) {}
