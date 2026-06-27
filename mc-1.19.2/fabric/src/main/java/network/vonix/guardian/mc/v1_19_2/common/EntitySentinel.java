/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.common;

import net.minecraft.core.Registry;
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
            ResourceLocation rl = Registry.ENTITY_TYPE.getKey(type);
            if (rl == null) {
                return UNKNOWN;
            }
            return "#mob:" + rl.getNamespace() + ":" + rl.getPath();
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }
}
