/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.platform;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.playerdata.PlayerStorage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.hytale.spi.PlayerDirectory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Live {@link PlayerRef} lookups plus a bounded read of
 * {@link PlayerStorage} for offline account names.
 *
 * <p>Online identity is {@link PlayerRef#getUuid()} and
 * {@link PlayerRef#getUsername()}. Name matching for
 * {@link #onlineByName(String)} is {@link NameMatching#EXACT_IGNORE_CASE},
 * so a prefix of another player's name does not resolve.
 *
 * <p>Offline, {@link PlayerStorage#load(UUID)} is the player-record API the
 * server exposes. It returns an entity holder, not a username. {@link PlayerRef}
 * has no codec, so a saved holder usually does not carry the account name.
 * {@link #nameOf(UUID)} still reads the record and returns the username when a
 * saved {@link PlayerRef} is present. It does not substitute a display name.
 * The wait is bounded by {@link #OFFLINE_NAME_WAIT_MILLIS}. Nothing is cached:
 * every call reads current server state, and no map is retained past the call.
 *
 * <p>Permission checks use {@link PlayerRef#hasPermission(String)} for an
 * online player and {@link PermissionsModule#hasPermission(UUID, String)}
 * otherwise. {@link #isOperator(UUID)} follows {@link HytaleOpLookup}.
 */
public final class HytalePlayerDirectory implements PlayerDirectory {

    /**
     * Upper bound for one offline record read. A missing record completes
     * immediately; this only caps a slow disk.
     */
    static final long OFFLINE_NAME_WAIT_MILLIS = 1_000L;

    private final HytaleOpLookup opLookup;

    public HytalePlayerDirectory() {
        this(new HytaleOpLookup());
    }

    /**
     * @param opLookup shared operator rule (never {@code null})
     */
    public HytalePlayerDirectory(HytaleOpLookup opLookup) {
        if (opLookup == null) {
            throw new IllegalArgumentException("opLookup must not be null");
        }
        this.opLookup = opLookup;
    }

    @Override
    public Optional<PlayerInfo> onlineByUuid(UUID uuid) {
        try {
            return info(HytaleOpLookup.online(uuid));
        } catch (Throwable failure) {
            logFailure("onlineByUuid", uuid, failure);
            return Optional.empty();
        }
    }

    @Override
    public Optional<PlayerInfo> onlineByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return Optional.empty();
            }
            return info(universe.getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE));
        } catch (Throwable failure) {
            logFailure("onlineByName", null, failure);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> nameOf(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        Optional<PlayerInfo> online = onlineByUuid(uuid);
        if (online.isPresent()) {
            return Optional.of(online.get().name());
        }
        return offlineName(uuid);
    }

    @Override
    public boolean hasPermission(UUID uuid, String permissionNode) {
        if (uuid == null || permissionNode == null || permissionNode.isBlank()) {
            return false;
        }
        try {
            PlayerRef player = HytaleOpLookup.online(uuid);
            if (player != null) {
                return player.hasPermission(permissionNode);
            }
            PermissionsModule permissions = HytaleOpLookup.permissions();
            if (permissions == null) {
                return false;
            }
            return permissions.hasPermission(uuid, permissionNode);
        } catch (Throwable failure) {
            logFailure("hasPermission", uuid, failure);
            return false;
        }
    }

    @Override
    public boolean isOperator(UUID uuid) {
        return opLookup.getOpLevel(uuid) == HytaleOpLookup.OPERATOR_LEVEL;
    }

    private Optional<String> offlineName(UUID uuid) {
        PlayerStorage storage = storage();
        if (storage == null) {
            return Optional.empty();
        }
        try {
            CompletableFuture<Holder<EntityStore>> future = storage.load(uuid);
            if (future == null) {
                return Optional.empty();
            }
            Holder<EntityStore> holder = future.get(OFFLINE_NAME_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (holder == null) {
                return Optional.empty();
            }
            ComponentType<EntityStore, PlayerRef> type = PlayerRef.getComponentType();
            if (type == null) {
                return Optional.empty();
            }
            return username(holder.getComponent(type));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Throwable failure) {
            logFailure("nameOf", uuid, failure);
            return Optional.empty();
        }
    }

    private static PlayerStorage storage() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            return universe.getPlayerStorage();
        } catch (Throwable failure) {
            logFailure("playerStorage", null, failure);
            return null;
        }
    }

    private static Optional<PlayerInfo> info(PlayerRef player) {
        if (player == null) {
            return Optional.empty();
        }
        UUID uuid = player.getUuid();
        Optional<String> name = username(player);
        if (uuid == null || name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PlayerInfo(uuid, name.get()));
    }

    private static Optional<String> username(PlayerRef player) {
        if (player == null) {
            return Optional.empty();
        }
        String name = player.getUsername();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    private static void logFailure(String action, UUID uuid, Throwable failure) {
        try {
            HytaleLogger.get(HytalePlayerDirectory.class.getName())
                    .at(Level.FINE)
                    .log("%s failed for %s (%s)", action, uuid,
                            failure == null ? "unknown" : failure.getClass().getName());
        } catch (Throwable ignored) {
            // Identity lookups must not fail because logging did.
        }
    }
}
