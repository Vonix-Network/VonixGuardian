/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

/**
 * Typed result of a {@link VonixGuardianAPI#signLookup} call — a single
 * sign edit (create / rewrite) at a specific coordinate.
 *
 * <p>Sign lines are joined at write time with the delimiter chosen by
 * {@code SubmitOps.submitSign}; consumers get the raw joined string via
 * {@link #lines()} and are expected to split as needed.
 *
 * @param time       epoch millis (UTC) when the sign was edited
 * @param actorUuid  player UUID if known; {@code null} for non-player sources
 * @param actorName  resolved name at event time
 * @param worldId    world / dimension key
 * @param x          block X of the sign
 * @param y          block Y of the sign
 * @param z          block Z of the sign
 * @param lines      the joined sign text at edit time; may be empty, never {@code null}
 * @param sourceTag  optional source classifier or {@code null}
 */
public record SignLookupResult(
        long time,
        java.util.UUID actorUuid,
        String actorName,
        String worldId,
        int x, int y, int z,
        String lines,
        String sourceTag,
        String signSide,
        String signDyeColor,
        Boolean signWaxed
) {}
