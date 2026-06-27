/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Scans an entity's persistent NBT for one of the well-known "owner" keys used
 * by Create, Mekanism, Iron's Spells, Ars Nouveau, and a long tail of other
 * mods. Used by step 5 of the universal attribution chain.
 *
 * <p>The keys checked, in priority order:
 * <ol>
 *   <li>{@code OwnerUUID}</li>
 *   <li>{@code Owner}</li>
 *   <li>{@code SummonerUUID}</li>
 *   <li>{@code summonerUUID}</li>
 *   <li>{@code Summoner}</li>
 *   <li>{@code deployerUUID}</li>
 *   <li>{@code controllerUUID}</li>
 * </ol>
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

    /**
     * Scan persistent NBT for owner-like UUID keys.
     *
     * @param entity the entity; {@code null} returns {@code null}
     * @return a UUID if any well-known key was present and valid, else {@code null}
     */
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

    /**
     * Scan an already-extracted NBT compound. Exposed for testing.
     *
     * @param tag the NBT compound; {@code null} returns {@code null}
     * @return UUID or {@code null}
     */
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
            // 1.21.1 / Mojmap: CompoundTag.hasUUID + getUUID for int-array uuid storage.
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
            // continue scanning
        }
        return null;
    }
}
