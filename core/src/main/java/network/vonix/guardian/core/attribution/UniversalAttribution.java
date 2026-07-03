/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import java.util.Objects;

/**
 * Stateless helpers that consult {@link TntPrimeMemory} and other core-side
 * attribution state to promote a raw entity-driven action to a
 * player-attributed one <em>before</em> the loader falls back to the sentinel.
 *
 * <p>The universal-attribution chain proper (rider / owner / projectile /
 * damage-history / NBT scan) lives in the loader-side resolver — it must touch
 * MC entity classes and cannot exist in {@code core}. This class holds the
 * <strong>loader-agnostic</strong> extensions to that chain — the ones that
 * only need a world id + block position + the shared memory caches.
 *
 * <p>Extensions currently owned:
 * <ul>
 *   <li>{@link #resolveTntPrime} — fixes CoreProtect-parity gap G-CP-2 for
 *       {@code PrimedTnt} explosions where the direct source entity is the
 *       TNT itself and vanilla loses the fire/redstone/dispenser/player
 *       chain.</li>
 * </ul>
 *
 * <p>Loader-side resolvers ({@code NeoForgeAttributionResolver},
 * {@code FabricAttributionResolver}, etc.) call these helpers as the FIRST
 * step for known-lossy source types (currently: {@code PrimedTnt}), and only
 * fall back to their normal chain when the memory lookup misses.
 *
 * @since 1.3.1
 */
public final class UniversalAttribution {

    private UniversalAttribution() {}

    /**
     * Consult {@link TntPrimeMemory} for a recorded prime at the entity's
     * origin BlockPos and, on hit, return a fully-populated {@link Attribution}
     * that reflects the human actor. On miss, return {@code null} so the
     * caller can fall back to its normal resolution chain.
     *
     * <p>The record is <strong>consumed</strong> on hit (removed from the
     * cache) to keep the map bounded across long-lived worlds — a single
     * PrimedTnt entity can only explode once, so no repeat lookup is needed.
     *
     * <p>Callers should invoke this only when the exploding entity is a
     * {@code PrimedTnt} (or an equivalent modded lossy source). Calling it on
     * every explosion is safe (returns {@code null} on miss) but wasteful.
     *
     * @param memory         the shared prime memory (never {@code null})
     * @param worldId        loader-side world id
     * @param originX        primed BlockPos X (typically {@code entity.blockPosition()})
     * @param originY        primed BlockPos Y
     * @param originZ        primed BlockPos Z
     * @param entitySentinel the primed-TNT sentinel (typically {@code "#tnt"} or
     *                       {@code "#mob:minecraft:tnt"}) used when we fall
     *                       through to a non-player attribution kind
     * @return a player-scoped {@link Attribution}, or {@code null} on miss
     */
    public static Attribution resolveTntPrime(TntPrimeMemory memory,
                                              String worldId,
                                              int originX, int originY, int originZ,
                                              String entitySentinel) {
        Objects.requireNonNull(memory, "memory");
        if (entitySentinel == null) entitySentinel = "#tnt";
        TntPrimeMemory.PrimeRecord rec = memory.consume(worldId, originX, originY, originZ);
        if (rec == null) return null;
        if (rec.actorUuid != null && rec.kind.isPlayer()) {
            // Preserve original kind (PLAYER_DIRECT for right-click F&S,
            // PLAYER_INDIRECT for dispenser/redstone/fire chains).
            return new Attribution(rec.actorUuid, rec.actorName, rec.kind, entitySentinel,
                    rec.kind == AttributionKind.PLAYER_DIRECT ? 0 : 1);
        }
        // Record present but no player — natural chain (fire spread with no
        // known lighter). Caller can still use rec.sourceTagHint to override
        // the sentinel-tag if desired.
        return null;
    }

    /**
     * Same as {@link #resolveTntPrime} but returns the raw {@link TntPrimeMemory.PrimeRecord}
     * so the caller can also read the {@code sourceTagHint} for its own
     * source-tag override. Used by the loader-side detonate handler.
     *
     * @param memory  the shared prime memory
     * @param worldId world id
     * @param x       origin X
     * @param y       origin Y
     * @param z       origin Z
     * @return the recorded prime, or {@code null} on miss
     */
    public static TntPrimeMemory.PrimeRecord consumeTntPrime(TntPrimeMemory memory,
                                                             String worldId,
                                                             int x, int y, int z) {
        Objects.requireNonNull(memory, "memory");
        return memory.consume(worldId, x, y, z);
    }
}
