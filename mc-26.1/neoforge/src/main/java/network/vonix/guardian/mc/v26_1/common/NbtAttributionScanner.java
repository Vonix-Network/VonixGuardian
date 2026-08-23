/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.common;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
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
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
            entity.saveWithoutId(out);
            return scan(out.buildResult());
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
            // Prefer string form; 26.1 CompoundTag.getString returns Optional.
            String s = tag.getStringOr(key, null);
            if (s != null && !s.isEmpty()) {
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
        } catch (Throwable ignored) {
            // continue scanning
        }
        return null;
    }
}
