/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.common;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats {@link Action} rows as chat lines for {@code /vg lookup} output.
 *
 * <p>Layout (CoreProtect-style):
 * <pre>
 *   &lt;timestamp ago&gt;: &lt;actor&gt; &lt;verb&gt; &lt;target&gt; (x,y,z) [rolled back]
 * </pre>
 */
public final class LookupFormatter {

    private LookupFormatter() {
        // utility
    }

    /**
     * Format a single action into a chat component.
     *
     * @param theme  active theme (nullable)
     * @param action the row; never {@code null}
     * @param now    current time in epoch millis
     * @return a {@link MutableComponent} ready to be sent
     */
    public static MutableComponent line(Theme theme, Action action, long now) {
        MutableComponent c = ChatRenderer.muted(theme, ago(now - action.timestamp()) + " ");
        c.append(ChatRenderer.primary(theme, safeName(action.actorName())));
        c.append(ChatRenderer.muted(theme, " " + verb(action) + " "));
        c.append(ChatRenderer.secondary(theme, safe(action.targetId())));
        c.append(ChatRenderer.muted(theme, " ("
                + action.x() + "," + action.y() + "," + action.z() + ")"));
        if (action.rolledBack()) {
            c.append(ChatRenderer.warning(theme, " [rolled back]"));
        }
        return c;
    }

    /**
     * Render a header for a paginated lookup result.
     *
     * @param theme active theme (nullable)
     * @param total total matching rows
     * @param page  1-based current page
     * @param pages total pages
     * @return header component
     */
    public static Component header(Theme theme, long total, int page, int pages) {
        MutableComponent c = ChatRenderer.primary(theme, "[VonixGuardian] ");
        c.append(ChatRenderer.secondary(theme,
                "Lookup: " + total + " result" + (total == 1 ? "" : "s")
                        + " (page " + Math.max(1, page) + "/" + Math.max(1, pages) + ")"));
        return c;
    }

    /**
     * Render a full page of {@code rows} with header.
     *
     * @param theme    active theme
     * @param rows     the actions to format
     * @param total    total matching rows (for header)
     * @param page     1-based current page
     * @param pageSize page size (used to compute total pages)
     * @param now      current time in epoch millis
     * @return immutable list of components in print order
     */
    public static List<Component> page(Theme theme, List<Action> rows, long total,
                                       int page, int pageSize, long now) {
        int pages = (int) Math.max(1L, (total + pageSize - 1) / Math.max(1, pageSize));
        List<Component> out = new ArrayList<>(rows.size() + 1);
        out.add(header(theme, total, page, pages));
        for (Action a : rows) {
            out.add(line(theme, a, now));
        }
        return out;
    }

    // ----------------------------------------------------------------------

    private static String safe(String s)        { return s == null ? "?" : s; }
    private static String safeName(String s)    { return s == null ? "#unknown" : s; }

    private static String verb(Action a) {
        return switch (a.type()) {
            case BLOCK_BREAK, BURN, FADE, BUCKET_FILL, LEAVES_DECAY,
                 PISTON_RETRACT, HANGING_BREAK              -> "broke";
            case BLOCK_PLACE, FORM, SPREAD, IGNITE, BUCKET_EMPTY,
                 PISTON_EXTEND, HANGING_PLACE                -> "placed";
            case CONTAINER_DEPOSIT, INVENTORY_DEPOSIT, HOPPER_PUSH -> "deposited";
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

    /** Coarse "Nm ago" / "Nh ago" / "Nd ago" rendering. */
    private static String ago(long deltaMs) {
        if (deltaMs < 0) deltaMs = 0;
        long s = deltaMs / 1000L;
        if (s < 60)        return s + "s ago";
        long m = s / 60L;
        if (m < 60)        return m + "m ago";
        long h = m / 60L;
        if (h < 24)        return h + "h ago";
        long d = h / 24L;
        if (d < 14)        return d + "d ago";
        long w = d / 7L;
        return w + "w ago";
    }
}
