/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Resolves an {@link Entity} or {@link EntityType} into a stable
 * {@code "#mob:<namespace>:<path>"} sentinel string.
 */
public final class EntitySentinel {

    public static final String UNKNOWN = "#mob:minecraft:unknown";

    private EntitySentinel() {
        // utility
    }

    public static String of(Entity entity) {
        if (entity == null) return UNKNOWN;
        return of(entity.getType());
    }

    public static String of(EntityType<?> type) {
        if (type == null) return UNKNOWN;
        try {
            ResourceLocation rl = Registry.ENTITY_TYPE.getKey(type);
            if (rl == null) return UNKNOWN;
            return "#mob:" + rl.getNamespace() + ":" + rl.getPath();
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }
}
