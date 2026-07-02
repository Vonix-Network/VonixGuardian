/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#usernameLookup} call — a single
 * username-change event.
 *
 * <p>{@code actorName} holds the <b>new</b> name that was in effect after the
 * change (matches how {@code SubmitOps.submitUsernameChange} seeds the row);
 * {@code previousName} holds the name in use before the change, extracted from
 * the underlying {@code Action.targetId()} field which is written as
 * {@code "<oldName> -> <newName>"}.
 *
 * @param time         epoch millis (UTC) when the change was recorded
 * @param actorUuid    player UUID (never {@code null})
 * @param actorName    the <b>new</b> name (name after the change)
 * @param previousName the <b>old</b> name (name before the change); {@code "?"}
 *                     if unrecorded
 */
public record UsernameLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String previousName
) {}
