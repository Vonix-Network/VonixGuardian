/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Translates a Minecraft {@link Level} into the canonical world-id string
 * VonixGuardian persists ({@code "minecraft:overworld"}, etc.).
 */
public final class WorldKey {

    private WorldKey() {
        // utility
    }

    public static String of(Level level) {
        if (level == null) {
            return "minecraft:overworld";
        }
        ResourceLocation rl = level.dimension().location();
        return rl != null ? rl.toString() : "minecraft:overworld";
    }
}
