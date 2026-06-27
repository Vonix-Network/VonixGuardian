/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.raid.Raider;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.Attribution;
import network.vonix.guardian.core.attribution.AttributionKind;
import network.vonix.guardian.core.attribution.AttributionResolver;
import network.vonix.guardian.core.attribution.DamageHistory;
import network.vonix.guardian.mc.v1_18_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_18_2.common.NbtAttributionScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Universal attribution resolver for Forge 1.18.2.
 *
 * <p>1.18.2 returns {@code Entity} (not {@code LivingEntity}) from
 * {@code Entity.getControllingPassenger()}, so the cast is identical to 1.20.1
 * — we just compare to {@link Player}.
 */
public final class ForgeAttributionResolver implements AttributionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeAttributionResolver.class);
    private static final int MAX_RECURSION = 4;

    private final DamageHistory damageHistory;
    private final MinecraftServer server;

    public ForgeAttributionResolver(DamageHistory damageHistory, MinecraftServer server) {
        this.damageHistory = Objects.requireNonNull(damageHistory, "damageHistory");
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Attribution resolve(Object handle, long nowMillis) {
        try {
            if (!(handle instanceof Entity e)) {
                return Attribution.unknown(EntitySentinel.UNKNOWN);
            }
            return resolveInner(e, nowMillis, 0);
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "AttributionResolver failure", t);
            return Attribution.unknown(EntitySentinel.UNKNOWN);
        }
    }

    private Attribution resolveInner(Entity e, long now, int depth) {
        String sentinel = EntitySentinel.of(e);
        if (depth >= MAX_RECURSION) {
            return Attribution.unknown(sentinel);
        }

        // 0. Direct player.
        if (e instanceof ServerPlayer sp) {
            return Attribution.direct(sp.getUUID(), sp.getName().getString(), sentinel);
        }

        // 1. Controlling rider — Entity on 1.18.2.
        Entity passenger = e.getControllingPassenger();
        if (passenger instanceof Player rider) {
            return Attribution.rider(rider.getUUID(), rider.getName().getString(), sentinel);
        }

        // 2. TamableAnimal owner.
        if (e instanceof TamableAnimal ta && ta.getOwnerUUID() != null) {
            UUID o = ta.getOwnerUUID();
            return Attribution.owner(o, lookupName(o), sentinel);
        }
        // 2b. OwnableEntity owner.
        if (e instanceof OwnableEntity oe && oe.getOwnerUUID() != null) {
            UUID o = oe.getOwnerUUID();
            return Attribution.owner(o, lookupName(o), sentinel);
        }

        // 3. Projectile owner — recurse.
        if (e instanceof Projectile p && p.getOwner() != null) {
            Attribution chain = resolveInner(p.getOwner(), now, depth + 1);
            if (chain.kind().isPlayer()) {
                return Attribution.projectile(chain.actorUuid(), chain.actorName(), sentinel, depth + 1);
            }
            return chain;
        }

        // 4. Recent damage window.
        UUID recent = damageHistory.lastPlayerToHit(e.getUUID(), now);
        if (recent != null) {
            return Attribution.indirect(recent, lookupName(recent), sentinel);
        }

        // 5. NBT scan.
        UUID nbtOwner = NbtAttributionScanner.scan(e);
        if (nbtOwner != null) {
            return Attribution.tamer(nbtOwner, lookupName(nbtOwner), sentinel, 2);
        }

        // 6. Natural classification.
        return Attribution.natural(classifyNatural(e), sentinel);
    }

    private AttributionKind classifyNatural(Entity e) {
        try {
            if (e instanceof Raider r && r.getCurrentRaid() != null) {
                return AttributionKind.NATURAL_RAID;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return AttributionKind.NATURAL_SPAWN;
    }

    public String lookupName(UUID uuid) {
        if (uuid == null) return "#unknown";
        try {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                return p.getName().getString();
            }
            if (server.getProfileCache() != null) {
                return server.getProfileCache().get(uuid)
                        .map(pr -> pr.getName())
                        .orElse(uuid.toString());
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return uuid.toString();
    }
}
