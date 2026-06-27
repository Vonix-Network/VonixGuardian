/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

/**
 * Loader-supplied resolver that produces an {@link Attribution} for an entity at
 * the moment it causes a logged action.
 *
 * <p>The loader (Fabric/Forge/NeoForge per MC version) supplies the implementation
 * because the resolution chain touches MC entity classes that are version-specific.
 * The {@code core} module only owns this contract.
 *
 * <p>The expected implementation strategy is documented for every loader as the
 * <strong>universal attribution chain</strong>:
 *
 * <ol>
 *   <li>{@code Entity#getControllingPassenger()} → if a Player, the player is the
 *       rider and is held responsible (PLAYER_RIDER).</li>
 *   <li>{@code TamableAnimal#getOwnerUUID()} → if non-null, owner (PLAYER_OWNER).</li>
 *   <li>{@code OwnableEntity#getOwnerUUID()} (1.20.2+) → same.</li>
 *   <li>{@code Projectile#getOwner()} → recurse on owner LivingEntity
 *       (PLAYER_PROJECTILE).</li>
 *   <li>{@link DamageHistory#lastPlayerToHit} within the configured window → indirect
 *       (PLAYER_INDIRECT).</li>
 *   <li>NBT scan for well-known owner keys (Create deployerUUID, Mekanism
 *       ownerUUID, generic Summoner / SummonerUUID / OwnerUUID) → tamer
 *       (PLAYER_TAMER).</li>
 *   <li>Classification of natural source (raid / spawn / structure / unknown).</li>
 * </ol>
 *
 * <p>This chain uses only vanilla Minecraft interfaces and types — modded entities
 * inherit those interfaces and are caught universally, with no per-mod code. The
 * NBT fallback covers the rare mod that re-implements ownership outside the
 * standard interfaces.
 *
 * <p>Implementations <strong>must not throw</strong>. On any failure, return
 * {@link Attribution#unknown(String)} with the best-effort entity sentinel.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface AttributionResolver {

    /**
     * Resolve attribution for a single entity reference.
     *
     * @param entityHandle a loader-side handle to the entity; the loader knows what
     *                     this is at runtime. Never {@code null}.
     * @param nowMillis    server clock at the moment of the action
     * @return non-null attribution; loaders must never throw
     */
    Attribution resolve(Object entityHandle, long nowMillis);
}
