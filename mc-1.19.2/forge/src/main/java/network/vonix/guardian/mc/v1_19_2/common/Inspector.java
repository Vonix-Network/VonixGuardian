/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.common;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static per-player inspection mode (the "{@code /vg inspect}" toggle).
 *
 * <p>While a player is in inspection mode, the loader's left-click handler
 * cancels block breaks and runs a position lookup instead, mirroring the
 * CoreProtect UX.
 *
 * <p>State is kept in a {@link ConcurrentHashMap} so toggle/check are
 * thread-safe — though in practice every call lands on the server thread.
 */
public final class Inspector {

    /** Backing list-style map: {@code uuid -> Boolean.TRUE} when active. */
    private static final Map<UUID, Boolean> STATE = new ConcurrentHashMap<>();

    private Inspector() {
        // utility
    }

    /**
     * @param uuid the player UUID; {@code null} returns {@code false}
     * @return true if the player is currently in inspection mode
     */
    public static boolean isInspecting(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return Boolean.TRUE.equals(STATE.get(uuid));
    }

    /**
     * Flip the inspection state and return the new value.
     *
     * @param uuid the player UUID; {@code null} returns {@code false}
     * @return the new inspection state ({@code true} = now inspecting)
     */
    public static boolean toggle(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        boolean next = !isInspecting(uuid);
        if (next) {
            STATE.put(uuid, Boolean.TRUE);
        } else {
            STATE.remove(uuid);
        }
        return next;
    }

    /** Clear all inspection state — typically called on server stop. */
    public static void clear() {
        STATE.clear();
    }

    /** Drop a single player's state — called on logout. */
    public static void forget(UUID uuid) {
        if (uuid != null) {
            STATE.remove(uuid);
        }
    }
}
