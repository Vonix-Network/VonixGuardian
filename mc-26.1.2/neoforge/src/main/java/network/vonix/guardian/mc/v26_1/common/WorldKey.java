/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.common;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * Translates a Minecraft {@link Level} into the canonical world-id string
 * VonixGuardian persists ({@code "minecraft:overworld"}, etc.).
 */
public final class WorldKey {

    private WorldKey() {
        // utility
    }

    /**
     * @param level any {@link Level} reference; {@code null} returns
     *              {@code "minecraft:overworld"} as a conservative default.
     * @return dimension key as {@code "<namespace>:<path>"}
     */
    public static String of(Level level) {
        if (level == null) {
            return "minecraft:overworld";
        }
        Identifier rl = level.dimension().identifier();
        return rl != null ? rl.toString() : "minecraft:overworld";
    }
}
