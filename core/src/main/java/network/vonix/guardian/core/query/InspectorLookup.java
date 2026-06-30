/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.storage.GuardianDao;

import java.util.ArrayList;
import java.util.List;

/**
 * Position-lookup helper used by the inspector wand (left-click) handler in
 * every loader module.
 *
 * <p>The CoreProtect equivalent is {@code BlockInspector.performInspectorLookup}:
 * given a world + (x,y,z), query the audit DB for the most recent actions at
 * that exact block and return a list of compact, ready-to-print chat lines.
 *
 * <p>Output is intentionally plain {@link String} because {@code core} is a
 * pure-Java module (no Minecraft types). Each loader wraps the strings in its
 * own {@code Component} type before sending them to the player.
 *
 * <p>Line format (CoreProtect-style):
 * <pre>
 *   &lt;ago&gt; - &lt;actor&gt; &lt;verb&gt; &lt;target&gt; (x,y,z)[ [rolled back]]
 * </pre>
 * The header line is prefixed with {@code ----- VonixGuardian -----} so the
 * loader can colour/format it if desired.
 */
public final class InspectorLookup {

    private InspectorLookup() {
        // utility
    }

    /**
     * Run the inspector lookup synchronously on the calling thread.
     *
     * <p>The DAO read-rate-limit semaphore (see {@code AbstractJdbcDao}) is
     * still respected; callers MUST schedule this off the server thread.
     *
     * @param dao     the live {@link GuardianDao}
     * @param worldId world key (e.g. {@code minecraft:overworld}); must not be {@code null}
     * @param x       block X
     * @param y       block Y
     * @param z       block Z
     * @param limit   max rows to return (clamped to {@code >=1})
     * @param now     epoch millis used as the reference for "Nm ago" rendering
     * @return ordered list of chat-ready plain-text lines; first line is the
     *         header, remaining lines are one per matching action, most recent
     *         first. Never {@code null}, never empty (always at least a header
     *         or a "no history" line).
     * @throws Exception on DAO failure
     */
    public static List<String> lookup(GuardianDao dao,
                                      String worldId,
                                      int x, int y, int z,
                                      int limit,
                                      long now) throws Exception {
        int effectiveLimit = Math.max(1, limit);

        QueryFilter filter = QueryFilter.builder()
                .worldSel(worldId != null
                        ? new QueryFilter.WorldSel(worldId, false)
                        : null)
                .radius(0)
                .center(x, y, z)
                .build();

        List<Action> rows = dao.query(filter, 0, effectiveLimit);

        List<String> out = new ArrayList<>(rows.size() + 1);
        out.add("----- VonixGuardian: lookup at (" + x + "," + y + "," + z + ") -----");
        if (rows.isEmpty()) {
            out.add("No block history at this location.");
            return out;
        }
        for (Action a : rows) {
            out.add(compactLine(a, now));
        }
        return out;
    }

    /**
     * Render a single {@link Action} as a compact plain-text line suitable for
     * the inspector wand output.
     *
     * @param a   the row; must not be {@code null}
     * @param now reference epoch millis for the "ago" rendering
     * @return one chat line
     */
    public static String compactLine(Action a, long now) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(ago(now - a.timestamp()))
          .append(" - ")
          .append(safeName(a.actorName()))
          .append(' ')
          .append(verb(a))
          .append(' ')
          .append(safe(a.targetId()))
          .append(" (")
          .append(a.x()).append(',').append(a.y()).append(',').append(a.z())
          .append(')');
        if (a.rolledBack()) {
            sb.append(" [rolled back]");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------

    private static String safe(String s)     { return s == null ? "?" : s; }
    private static String safeName(String s) { return s == null ? "#unknown" : s; }

    private static String verb(Action a) {
        return switch (a.type()) {
            case BLOCK_BREAK, BURN, FADE, BUCKET_FILL, LEAVES_DECAY,
                 PISTON_RETRACT, HANGING_BREAK              -> "broke";
            case BLOCK_PLACE, FORM, SPREAD, IGNITE, BUCKET_EMPTY,
                 PISTON_EXTEND, HANGING_PLACE                -> "placed";
            case CONTAINER_DEPOSIT, INVENTORY_DEPOSIT, HOPPER_PUSH   -> "deposited";
            case CONTAINER_WITHDRAW, INVENTORY_WITHDRAW, HOPPER_PULL -> "withdrew";
            case ITEM_DROP        -> "dropped";
            case ITEM_PICKUP      -> "picked up";
            case ITEM_CRAFT       -> "crafted";
            case ENTITY_KILL      -> "killed";
            case ENTITY_SPAWN     -> "spawned";
            case ENTITY_INTERACT  -> "interacted with";
            case ENTITY_CHANGE_BLOCK -> "changed block";
            case EXPLOSION        -> "exploded";
            case CHAT             -> "said";
            case COMMAND          -> "ran";
            case SIGN             -> "signed";
            case SESSION_JOIN     -> "joined";
            case SESSION_LEAVE    -> "left";
            case USERNAME_CHANGE  -> "renamed";
            case DISPENSE         -> "dispensed";
            case STRUCTURE_GROW   -> "grew";
            case PORTAL_CREATE    -> "created portal";
            case CHUNK_POPULATE   -> "populated";
            case CLICK            -> "clicked";
        };
    }

    private static String ago(long deltaMs) {
        if (deltaMs < 0) deltaMs = 0;
        long s = deltaMs / 1000L;
        if (s < 60)  return s + "s ago";
        long m = s / 60L;
        if (m < 60)  return m + "m ago";
        long h = m / 60L;
        if (h < 24)  return h + "h ago";
        long d = h / 24L;
        if (d < 14)  return d + "d ago";
        long w = d / 7L;
        return w + "w ago";
    }
}
