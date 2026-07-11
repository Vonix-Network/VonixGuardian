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

    // ------------------------------------------------------------------ C2 fire

    /**
     * Verdict for a fire ({@code IGNITE}/{@code BURN}) event that may have been
     * caused by an entity block change. Produced by {@link #resolveFireCauser}.
     *
     * @since 1.3.10
     */
    public enum FireVerdict {
        /**
         * A nearby allowlisted entity caused this fire — the caller should log
         * it attributed to that entity (see {@link FireCauser#actorName} /
         * {@link FireCauser#sourceTag}) so a region+time rollback restores the
         * broken block <em>and</em> clears the fire together.
         */
        PAIR,
        /**
         * A nearby <em>non-allowlisted</em> entity caused this fire — it is
         * orphan side-effect noise from a creature we deliberately don't audit.
         * The caller should drop the event entirely.
         */
        SUPPRESS,
        /**
         * No entity caused this fire (player flint&amp;steel, lightning, lava,
         * natural spread). The caller should keep its existing world-fire
         * behaviour unchanged.
         */
        PASSTHROUGH
    }

    /**
     * Immutable result of {@link #resolveFireCauser}: the verdict plus the
     * attribution to stamp on the fire event when {@link #verdict} is
     * {@link FireVerdict#PAIR}.
     *
     * @since 1.3.10
     */
    public static final class FireCauser {
        public final FireVerdict verdict;
        public final java.util.UUID actorUuid;
        public final String actorName;
        public final String sourceTag;

        private FireCauser(FireVerdict verdict, java.util.UUID actorUuid,
                           String actorName, String sourceTag) {
            this.verdict = verdict;
            this.actorUuid = actorUuid;
            this.actorName = actorName;
            this.sourceTag = sourceTag;
        }

        static final FireCauser PASSTHROUGH =
                new FireCauser(FireVerdict.PASSTHROUGH, null, null, null);
        static final FireCauser SUPPRESS =
                new FireCauser(FireVerdict.SUPPRESS, null, null, null);

        static FireCauser pair(java.util.UUID uuid, String name, String tag) {
            return new FireCauser(FireVerdict.PAIR, uuid, name, tag);
        }
    }

    /**
     * Resolve whether a fire event at {@code (x,y,z)} was caused by a recent
     * nearby entity block change, and how the caller should treat it.
     *
     * <p>Consumes the freshest {@link FireCauserMemory.CauserRecord} within the
     * memory's radius so a given break pairs with at most one fire event.</p>
     *
     * <ul>
     *   <li>allowlisted causer → {@link FireVerdict#PAIR} carrying the entity's
     *       attribution (actor + {@code #entity}-family source tag);</li>
     *   <li>non-allowlisted causer → {@link FireVerdict#SUPPRESS};</li>
     *   <li>no causer → {@link FireVerdict#PASSTHROUGH}.</li>
     * </ul>
     *
     * @param memory  the shared fire-causer memory (never {@code null})
     * @param worldId loader-side world id
     * @param x       fire BlockPos X
     * @param y       fire BlockPos Y
     * @param z       fire BlockPos Z
     * @return a never-{@code null} {@link FireCauser} verdict
     * @since 1.3.10
     */
    public static FireCauser resolveFireCauser(FireCauserMemory memory,
                                               String worldId,
                                               int x, int y, int z) {
        Objects.requireNonNull(memory, "memory");
        FireCauserMemory.CauserRecord rec = memory.consume(worldId, x, y, z);
        if (rec == null) return FireCauser.PASSTHROUGH;
        if (!rec.allowlisted) return FireCauser.SUPPRESS;
        return FireCauser.pair(rec.actorUuid, rec.actorName, rec.sourceTagHint);
    }
}
