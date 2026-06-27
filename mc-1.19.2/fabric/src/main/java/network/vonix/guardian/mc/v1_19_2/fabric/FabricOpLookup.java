/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.ServerOpListEntry;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.perms.OpLevelFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Fabric-side implementation of {@link OpLevelFallback}. Identical to the
 * NeoForge implementation — Mojmap server op-list API is shared.
 */
public final class FabricOpLookup implements OpLevelFallback {

    private static final Logger LOG = LoggerFactory.getLogger(FabricOpLookup.class);

    private final MinecraftServer server;

    public FabricOpLookup(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public int getOpLevel(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        try {
            GameProfile profile = server.getProfileCache() != null
                    ? server.getProfileCache().get(uuid).orElse(new GameProfile(uuid, ""))
                    : new GameProfile(uuid, "");
            ServerOpListEntry entry = server.getPlayerList().getOps().get(profile);
            if (entry == null) {
                return 0;
            }
            return entry.getLevel();
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "OpLookup failed for {}", uuid, t);
            return 0;
        }
    }
}
