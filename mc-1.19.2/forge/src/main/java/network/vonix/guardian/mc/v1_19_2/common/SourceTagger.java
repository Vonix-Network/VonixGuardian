/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.common;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.event.Sentinel;

/**
 * Maps Minecraft cause objects (damage sources, entities, block states) to a
 * stable, human-readable {@code sourceTag} string per SHARED-LOADER-CONTRACTS § 8.
 *
 * <p>The returned tag is consumed by the audit DAO unchanged; values that already
 * begin with {@code "#"} are sentinels per {@link Sentinel}.
 */
public final class SourceTagger {

    private SourceTagger() {
        // utility
    }

    /**
     * Tag a {@link DamageSource}.
     *
     * @param src damage source; {@code null} returns {@code null}
     * @return source-tag string, or {@code null} if no good tag is available
     */
    public static String tag(DamageSource src) {
        if (src == null) {
            return null;
        }
        try {
            String msg = src.getMsgId();
            if (msg != null) {
                switch (msg) {
                    case "fall":            return Sentinel.FALL;
                    case "drown":           return Sentinel.DROWN;
                    case "inWall":          return Sentinel.SUFFOCATE;
                    case "lava":            return Sentinel.LAVA;
                    case "inFire":
                    case "onFire":          return Sentinel.FIRE;
                    case "lightningBolt":   return "#lightning";
                    case "magic":           return Sentinel.MAGIC;
                    case "explosion":
                    case "explosion.player": return Sentinel.EXPLOSION;
                    default:
                        // fall through to entity-based inspection
                }
            }
        } catch (Throwable ignored) {
            // defensive
        }
        Entity direct = src.getDirectEntity();
        if (direct != null) {
            String fromDirect = tag(direct);
            if (fromDirect != null) {
                return fromDirect;
            }
        }
        Entity causing = src.getEntity();
        if (causing != null) {
            return tag(causing);
        }
        return null;
    }

    /**
     * Tag based on a causing/direct {@link Entity}.
     *
     * @param e the entity; {@code null} returns {@code null}
     * @return sentinel-prefixed tag or modded identifier
     */
    public static String tag(Entity e) {
        if (e == null) {
            return null;
        }
        if (e instanceof Creeper)        return Sentinel.CREEPER;
        if (e instanceof PrimedTnt)      return Sentinel.TNT;
        if (e instanceof WitherSkull)    return Sentinel.WITHER_SKULL;
        if (e instanceof LargeFireball || e instanceof SmallFireball) return Sentinel.FIREBALL;
        if (e instanceof EnderDragon)    return "#dragon";
        if (e instanceof AbstractArrow)  return "#arrow";
        if (e instanceof ThrownTrident)  return "#trident";

        try {
            EntityType<?> type = e.getType();
            ResourceLocation rl = Registry.ENTITY_TYPE.getKey(type);
            if (rl == null) {
                return null;
            }
            return "#mob:" + rl.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Tag based on a {@link BlockState} that caused something (e.g. fire spread,
     * fluid flow).
     *
     * @param state block state; {@code null} returns {@code null}
     * @return canonical sentinel for the responsible block class, or {@code null}
     */
    public static String tag(BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            if (rl == null) {
                return null;
            }
            String path = rl.getPath();
            if (path.contains("lava")) return Sentinel.LAVA;
            if (path.contains("water")) return "#fluid:water";
            if (path.equals("fire") || path.contains("fire")) return Sentinel.FIRE;
            if (path.equals("tnt")) return Sentinel.TNT;
            if (path.contains("piston")) return Sentinel.PISTON;
            if (path.contains("bed")) return Sentinel.BED;
            if (path.contains("respawn_anchor")) return Sentinel.RESPAWN_ANCHOR;
            String ns = rl.getNamespace();
            if (!"minecraft".equals(ns)) {
                return "#modded:" + ns;
            }
            return "#block:" + rl;
        } catch (Throwable t) {
            return null;
        }
    }
}
