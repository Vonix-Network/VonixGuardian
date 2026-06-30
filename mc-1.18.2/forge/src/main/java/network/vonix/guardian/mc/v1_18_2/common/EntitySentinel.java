/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * Resolves an {@link Entity} or {@link EntityType} into a stable
 * {@code "#mob:<namespace>:<path>"} sentinel.
 *
 * <p>Uses a reflection-resolved {@code Entity::getType} handle so the call
 * works whether the runtime exposes the Mojang name ({@code getType}) or
 * the SRG name ({@code m_6095_}). Some Forge 1.18.2 modpacks with deep
 * mixin/coremod transformation chains have been observed to leave one
 * name unresolved on the bytecode classloader path, producing
 * {@link NoSuchMethodError} once per entity-join — hammering logs without
 * actually breaking gameplay.</p>
 */
public final class EntitySentinel {

    public static final String UNKNOWN = "#mob:minecraft:unknown";

    private static final MethodHandle ENTITY_GET_TYPE = resolveGetType();

    private EntitySentinel() {
        // utility
    }

    private static MethodHandle resolveGetType() {
        // Try the Mojang-mapped name first, then the SRG name. Whichever
        // the runtime exposes wins.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodType mt = MethodType.methodType(EntityType.class);
        for (String name : new String[]{"getType", "m_6095_"}) {
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

    public static String of(Entity entity) {
        if (entity == null) {
            return UNKNOWN;
        }
        EntityType<?> type = null;
        if (ENTITY_GET_TYPE != null) {
            try {
                type = (EntityType<?>) ENTITY_GET_TYPE.invoke(entity);
            } catch (Throwable ignore) {
                // fall through to direct call (last-ditch)
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
