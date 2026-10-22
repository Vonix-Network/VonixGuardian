/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player inspect toggle for {@code /guardian inspect}.
 *
 * <p>This cell only stores the flag. Block-click capture belongs to the
 * capture cell, which can call {@link #isInspecting(UUID)} on its own event
 * thread. {@link #forget(UUID)} is for logout; {@link #clear()} is for
 * shutdown. Neither is wired here because this cell does not own events.
 */
public final class InspectMode {

    private static final ConcurrentHashMap<UUID, Boolean> ACTIVE = new ConcurrentHashMap<>();

    private InspectMode() {
    }

    public static boolean isInspecting(UUID uuid) {
        return uuid != null && Boolean.TRUE.equals(ACTIVE.get(uuid));
    }

    /** @return the state after the flip; {@code false} when {@code uuid} is null */
    public static boolean toggle(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        boolean next = !isInspecting(uuid);
        if (next) {
            ACTIVE.put(uuid, Boolean.TRUE);
        } else {
            ACTIVE.remove(uuid);
        }
        return next;
    }

    public static void forget(UUID uuid) {
        if (uuid != null) {
            ACTIVE.remove(uuid);
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
