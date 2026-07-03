/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Resolves an {@link Entity} or {@link EntityType} into a stable
 * {@code "#mob:<namespace>:<path>"} sentinel string used as a non-player
 * actor name in audit rows.
 */
public final class EntitySentinel {

    /** Used when no better information is available. */
    public static final String UNKNOWN = "#mob:minecraft:unknown";

    // v1.3.1 X2 — dedicated entity-caused block-change sourceTag prefixes.
    // Used as the {@code sourceTag} value on submitted Actions, mirroring the
    // {@code #fire} / {@code #natural} / {@code #dispenser} convention from the
    // mixin-hot-events kill-switch registry so operators can toggle these
    // categories independently and Ledger-parity attribution stays legible.
    /** {@link net.minecraft.world.entity.boss.enderdragon.EnderDragon} — head-collision block break. */
    public static final String SRC_ENDER_DRAGON = "#enderdragon";
    /** {@link net.minecraft.world.entity.monster.Ravager} — aiStep destroyBlock (crops, leaves). */
    public static final String SRC_RAVAGER      = "#ravager";
    /** SnowGolem — aiStep snow trail. */
    public static final String SRC_SNOW_GOLEM   = "#snow_golem";
    /** {@link net.minecraft.world.entity.item.FallingBlockEntity} — fall (break) and land (place). */
    public static final String SRC_GRAVITY      = "#gravity";
    /** {@link net.minecraft.world.entity.LightningBolt} — spawnFire ignite. */
    public static final String SRC_LIGHTNING    = "#lightning";
    /** {@link net.minecraft.world.entity.monster.Silverfish} — infest block state change. */
    public static final String SRC_SILVERFISH   = "#silverfish";

    private EntitySentinel() {
        // utility
    }

    /**
     * Resolve a sentinel for a live entity.
     *
     * @param entity the entity; {@code null} returns {@link #UNKNOWN}
     * @return {@code "#mob:<ns>:<path>"}
     */
    public static String of(Entity entity) {
        if (entity == null) {
            return UNKNOWN;
        }
        return of(entity.getType());
    }

    /**
     * Resolve a sentinel for an entity type alone.
     *
     * @param type the type; {@code null} returns {@link #UNKNOWN}
     * @return {@code "#mob:<ns>:<path>"}
     */
    public static String of(EntityType<?> type) {
        if (type == null) {
            return UNKNOWN;
        }
        try {
            ResourceLocation rl = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (rl == null) {
                return UNKNOWN;
            }
            return "#mob:" + rl.getNamespace() + ":" + rl.getPath();
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }
}
