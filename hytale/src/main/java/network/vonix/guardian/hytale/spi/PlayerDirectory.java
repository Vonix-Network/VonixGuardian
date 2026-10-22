/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves Hytale player identity for the audit engine and evaluates command
 * permissions. Implemented by the platform cell; consumed by capture and
 * command cells. All methods must tolerate unknown/offline players.
 */
public interface PlayerDirectory {

    /** Compact identity used by the engine. */
    record PlayerInfo(UUID uuid, String name) {
        public PlayerInfo {
            if (uuid == null) {
                throw new IllegalArgumentException("uuid must not be null");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }

    Optional<PlayerInfo> onlineByUuid(UUID uuid);

    Optional<PlayerInfo> onlineByName(String name);

    /** Offline-capable name lookup; empty when never seen. */
    Optional<String> nameOf(UUID uuid);

    /** {@code true} if this player currently holds {@code permissionNode}. */
    boolean hasPermission(UUID uuid, String permissionNode);

    /** {@code true} if this player is treated as a server operator/admin. */
    boolean isOperator(UUID uuid);
}
