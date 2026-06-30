/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.common;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Resolves an {@link Entity} or {@link EntityType} into a stable
 * {@code "#mob:<namespace>:<path>"} sentinel string used as a non-player
 * actor name in audit rows.
 *
 * <p>NeoForge 1.21.1 ships Mojang official names at runtime, so
 * {@code Entity#getType} should always resolve. We still go through a
 * {@link MethodHandle} resolved at class-init time as a defence against
 * pathological mixin/coremod chains observed on heavy modpacks that
 * occasionally fail to link one name on the bytecode classloader path,
 * producing per-entity-join {@link NoSuchMethodError} spam.</p>
 */
public final class EntitySentinel {

    /** Used when no better information is available. */
    public static final String UNKNOWN = "#mob:minecraft:unknown";

    private static final MethodHandle ENTITY_GET_TYPE = resolveGetType();

    private EntitySentinel() {
        // utility
    }

    private static MethodHandle resolveGetType() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodType mt = MethodType.methodType(EntityType.class);
        // NeoForge 1.21.1: only "getType" exists. We still try a list for
        // forward-compat / parity with the Forge 1.20.1 sentinel.
        for (String name : new String[]{"getType"}) {
            try {
                return lookup.findVirtual(Entity.class, name, mt);
            } catch (NoSuchMethodException | IllegalAccessException ignore) {
                // try next
            }
        }
        // Last resort: reflective scan for any zero-arg method returning EntityType.
        for (Method m : Entity.class.getMethods()) {
            if (m.getParameterCount() == 0 && EntityType.class.isAssignableFrom(m.getReturnType())) {
                try {
                    return lookup.unreflect(m);
                } catch (IllegalAccessException ignore) {
                    // keep scanning
                }
            }
        }
        return null;
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
        EntityType<?> type = null;
        if (ENTITY_GET_TYPE != null) {
            try {
                type = (EntityType<?>) ENTITY_GET_TYPE.invoke(entity);
            } catch (Throwable ignore) {
                // fall through to direct call
            }
        }
        if (type == null) {
            try {
                type = entity.getType();
            } catch (Throwable ignore) {
                return UNKNOWN;
            }
        }
        return of(type);
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
