/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static per-player inspection mode (the "{@code /vg inspect}" toggle).
 */
public final class Inspector {

    private static final Map<UUID, Boolean> STATE = new ConcurrentHashMap<>();

    private Inspector() {
        // utility
    }

    public static boolean isInspecting(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return Boolean.TRUE.equals(STATE.get(uuid));
    }

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

    public static void clear() {
        STATE.clear();
    }

    public static void forget(UUID uuid) {
        if (uuid != null) {
            STATE.remove(uuid);
        }
    }
}
