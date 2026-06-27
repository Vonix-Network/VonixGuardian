/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

/**
 * Classifies how a logged action ended up attributed to a player UUID — or to a
 * natural-world source.
 *
 * <p>This is the "who really did this" classification VonixGuardian computes for
 * every entity-driven action (block break by mob, explosion by projectile, etc.).
 * The {@link network.vonix.guardian.core.attribution.UniversalAttribution}
 * resolver produces this on the loader side and the result is encoded into
 * {@link network.vonix.guardian.core.action.Action#meta()} as JSON.
 *
 * <p>Kinds are ordered from "most confident the player is responsible" to
 * "definitely not a player": PLAYER_DIRECT &gt; PLAYER_RIDER &gt; PLAYER_OWNER &gt;
 * PLAYER_TAMER &gt; PLAYER_PROJECTILE &gt; PLAYER_INDIRECT &gt; NATURAL_RAID &gt;
 * NATURAL_SPAWN &gt; NATURAL_STRUCTURE &gt; NATURAL_UNKNOWN &gt; UNKNOWN.
 *
 * <p>Operators query by kind via {@code /vg lookup attr:<kind>}.
 *
 * @since 0.1.0
 */
public enum AttributionKind {

    /** Player took the action directly (e.g. broke the block themselves). */
    PLAYER_DIRECT,

    /**
     * A player was the controlling passenger of the entity that took the action.
     * Catches dragon riders (Isle of Berk, Ice and Fire, Dragon Mounts), every
     * flying-mount mod, every "ridden ravager" exploit.
     */
    PLAYER_RIDER,

    /**
     * A player is the registered owner of the entity that took the action. The
     * entity acted on its own (uncommanded) but counts under the owner.
     * Resolved via {@code TamableAnimal.getOwnerUUID()} or {@code OwnableEntity.getOwnerUUID()}.
     */
    PLAYER_OWNER,

    /**
     * Like {@link #PLAYER_OWNER} but the ownership was deduced from NBT (mods
     * that don't use the standard ownable interfaces — Create contraptions,
     * Mekanism digital miners, magic-mod summons).
     */
    PLAYER_TAMER,

    /**
     * A projectile launched by a player caused the action. The player UUID is
     * resolved by recursing on the projectile's {@code getOwner()}.
     */
    PLAYER_PROJECTILE,

    /**
     * A player hit the entity within the recent damage window and the entity
     * subsequently took a destructive action — the player is held indirectly
     * responsible (the "berserk ravager" pattern).
     */
    PLAYER_INDIRECT,

    /** A raid-spawned mob (ravager, vindicator in raid) caused the action. */
    NATURAL_RAID,

    /** Random world-spawned mob caused the action (no player chain). */
    NATURAL_SPAWN,

    /** Structure-bound entity (dungeon mob, boss, generated guardian) caused the action. */
    NATURAL_STRUCTURE,

    /** Mob caused the action but the natural sub-classification is unknown. */
    NATURAL_UNKNOWN,

    /** Source could not be determined at all (defensive fallback; should be rare). */
    UNKNOWN;

    /** True iff the kind names a player UUID as the responsible party. */
    public boolean isPlayer() {
        return switch (this) {
            case PLAYER_DIRECT, PLAYER_RIDER, PLAYER_OWNER, PLAYER_TAMER,
                 PLAYER_PROJECTILE, PLAYER_INDIRECT -> true;
            default -> false;
        };
    }

    /** True iff the action was natural (no player attribution). */
    public boolean isNatural() {
        return switch (this) {
            case NATURAL_RAID, NATURAL_SPAWN, NATURAL_STRUCTURE, NATURAL_UNKNOWN -> true;
            default -> false;
        };
    }
}
