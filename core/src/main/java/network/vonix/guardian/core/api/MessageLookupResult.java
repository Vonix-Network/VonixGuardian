/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#chatLookup} or
 * {@link VonixGuardianAPI#commandLookup} call — a single chat message or
 * command entry.
 *
 * <p>Chat/command events are <b>non-positional</b> — {@code worldId} is still
 * captured (the world the player was in at send-time) but {@code x/y/z} are
 * conventionally zero. See {@link network.vonix.guardian.core.action.Action#isPositional()}.
 *
 * @param time      epoch millis (UTC) when the message was sent
 * @param actorUuid player UUID (never {@code null} for chat/command)
 * @param actorName resolved name at send time
 * @param worldId   world the player was in
 * @param message   the full message body (chat text or command including leading {@code /})
 * @param kind      {@code "chat"} or {@code "command"} — the canonical
 *                  {@link network.vonix.guardian.core.action.ActionType#token()}
 */
public record MessageLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String worldId,
        String message,
        String kind
) {}
