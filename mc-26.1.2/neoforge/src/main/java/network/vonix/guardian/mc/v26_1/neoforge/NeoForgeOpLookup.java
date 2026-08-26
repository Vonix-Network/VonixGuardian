/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.neoforge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.perms.OpLevelFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * NeoForge-side implementation of {@link OpLevelFallback}.
 *
 * <p>Looks the player up in the server op-list. Returns {@code 0} for any
 * unknown / non-opped UUID. Never throws.
 */
public final class NeoForgeOpLookup implements OpLevelFallback {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeOpLookup.class);

    private final MinecraftServer server;

    /**
     * @param server live Minecraft server (never {@code null})
     */
    public NeoForgeOpLookup(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public int getOpLevel(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        try {
            NameAndId id = new NameAndId(uuid, "");
            ServerOpListEntry entry = server.getPlayerList().getOps().get(id);
            if (entry == null) {
                return 0;
            }
            return entry.permissions().level().id();
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "OpLookup failed for {}", uuid, t);
            return 0;
        }
    }
}
