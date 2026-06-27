/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Scans an entity's persistent NBT for well-known owner keys (Create, Mekanism,
 * Iron's Spells, Ars Nouveau, etc.). Step 5 of the universal attribution chain.
 */
public final class NbtAttributionScanner {

    private static final String[] KEYS = {
            "OwnerUUID", "Owner",
            "SummonerUUID", "summonerUUID", "Summoner",
            "deployerUUID", "controllerUUID"
    };

    private NbtAttributionScanner() {
        // utility
    }

    public static UUID scan(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            return scan(tag);
        } catch (Throwable t) {
            return null;
        }
    }

    public static UUID scan(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        for (String key : KEYS) {
            UUID u = readUuid(tag, key);
            if (u != null) {
                return u;
            }
        }
        return null;
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        try {
            if (!tag.contains(key)) {
                return null;
            }
            if (tag.hasUUID(key)) {
                return tag.getUUID(key);
            }
            if (tag.contains(key, 8 /*StringTag*/)) {
                String s = tag.getString(key);
                if (s != null && !s.isEmpty()) {
                    return UUID.fromString(s);
                }
            }
        } catch (Throwable ignored) {
            // continue
        }
        return null;
    }
}
