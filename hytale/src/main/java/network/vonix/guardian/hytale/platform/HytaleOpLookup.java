/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.platform;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import network.vonix.guardian.core.perms.OpLevelFallback;

import java.util.UUID;
import java.util.logging.Level;

/**
 * {@link OpLevelFallback} backed by Hytale's permission data.
 *
 * <p>Hytale has no vanilla op-level integer. Builtin groups in
 * {@link HytalePermissionsProvider} are a parent chain, and the only builtin
 * grant on that chain is {@code *} ({@link PermissionsModule#ROOT}) on
 * {@link HytalePermissionsProvider#OP_GROUP} ({@code hytale:Admin}):
 *
 * <ul>
 *   <li>{@code hytale:None} — no parent, no permissions</li>
 *   <li>{@code hytale:Adventurer} — parent None, no permissions
 *       ({@link HytalePermissionsProvider#DEFAULT_GROUP})</li>
 *   <li>{@code hytale:Builder} — parent Adventurer, no permissions</li>
 *   <li>{@code hytale:WorldEditor} — parent Builder, no permissions</li>
 *   <li>{@code hytale:ServerEditor} — parent WorldEditor, no permissions</li>
 *   <li>{@code hytale:Admin} — parent ServerEditor, permission {@code *}
 *       ({@link HytalePermissionsProvider#OP_GROUP})</li>
 * </ul>
 *
 * <p>Mapping onto the {@code [0, 4]} range {@link OpLevelFallback} requires:
 *
 * <ul>
 *   <li>{@link #OPERATOR_LEVEL} ({@code 4}) when the player holds
 *       {@link PermissionsModule#ROOT}. Online players are asked through
 *       {@link PlayerRef#hasPermission(String)}; everyone else through
 *       {@link PermissionsModule#hasPermission(UUID, String)}. Both read the
 *       same module. A custom group that grants {@code *} is level 4 as well.
 *       An Admin group whose {@code *} grant was removed is level 0.</li>
 *   <li>{@link #NONE_LEVEL} ({@code 0}) for everyone else, including unknown
 *       UUIDs and a missing permissions module.</li>
 * </ul>
 *
 * <p>Levels 1, 2, and 3 are never returned. The intermediate builtin groups
 * carry no permission nodes, so numbering them would invent a scale the
 * platform does not have. {@code OpLevelFallback} receives only a UUID, so a
 * partial {@code vonixguardian.*} grant cannot be expressed here. That grant
 * is visible from {@link HytalePlayerDirectory#hasPermission(UUID, String)}.
 *
 * <p>Never throws. Failures are logged as the UUID and the exception class.
 */
public final class HytaleOpLookup implements OpLevelFallback {

    /** Returned when the player holds {@link PermissionsModule#ROOT}. */
    public static final int OPERATOR_LEVEL = 4;

    /** Returned when the player does not hold {@link PermissionsModule#ROOT}. */
    public static final int NONE_LEVEL = 0;

    public HytaleOpLookup() {
        // PermissionsModule and Universe are process singletons.
    }

    @Override
    public int getOpLevel(UUID uuid) {
        if (uuid == null) {
            return NONE_LEVEL;
        }
        try {
            PlayerRef online = online(uuid);
            if (online != null) {
                return online.hasPermission(PermissionsModule.ROOT) ? OPERATOR_LEVEL : NONE_LEVEL;
            }
            PermissionsModule permissions = permissions();
            if (permissions == null) {
                return NONE_LEVEL;
            }
            return permissions.hasPermission(uuid, PermissionsModule.ROOT)
                    ? OPERATOR_LEVEL : NONE_LEVEL;
        } catch (Throwable failure) {
            logFailure(uuid, failure);
            return NONE_LEVEL;
        }
    }

    static PlayerRef online(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            return universe.getPlayer(uuid);
        } catch (Throwable failure) {
            logFailure(uuid, failure);
            return null;
        }
    }

    static PermissionsModule permissions() {
        try {
            return PermissionsModule.get();
        } catch (Throwable failure) {
            logFailure(null, failure);
            return null;
        }
    }

    private static void logFailure(UUID uuid, Throwable failure) {
        try {
            HytaleLogger.get(HytaleOpLookup.class.getName())
                    .at(Level.FINE)
                    .log("Op lookup failed for %s (%s)", uuid,
                            failure == null ? "unknown" : failure.getClass().getName());
        } catch (Throwable ignored) {
            // A logging failure must not change the deny result.
        }
    }
}
