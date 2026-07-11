/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats {@link Action} rows as chat lines for {@code /vg lookup} output.
 */
public final class LookupFormatter {

    private LookupFormatter() {
        // utility
    }

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

    public static Component header(Theme theme, long total, int page, int pages) {
        MutableComponent c = ChatRenderer.primary(theme, "[VonixGuardian] ");
        c.append(ChatRenderer.secondary(theme,
                "Lookup: " + total + " result" + (total == 1 ? "" : "s")
                        + " (page " + Math.max(1, page) + "/" + Math.max(1, pages) + ")"));
        return c;
    }

    public static List<Component> page(Theme theme, List<Action> rows, long total,
                                       int page, int pageSize, long now) {
        return page(theme, rows, total, page, pageSize, now, null);
    }

    /**
     * Render a full page of {@code rows} with header and, when more pages
     * remain, a footer hint telling the viewer how to fetch the next page.
     *
     * @param filter the raw filter string the viewer supplied (nullable); used
     *               to build the next-page command hint. Any leading page token
     *               has already been stripped by the command layer.
     */
    public static List<Component> page(Theme theme, List<Action> rows, long total,
                                       int page, int pageSize, long now, String filter) {
        int pages = (int) Math.max(1L, (total + pageSize - 1) / Math.max(1, pageSize));
        int curr = Math.max(1, page);
        List<Component> out = new ArrayList<>(rows.size() + 2);
        out.add(header(theme, total, curr, pages));
        for (Action a : rows) {
            out.add(line(theme, a, now));
        }
        if (curr < pages) {
            out.add(footer(theme, curr + 1, pageSize, filter));
        }
        return out;
    }

    /**
     * Render the "next page" footer hint shown when more results remain.
     *
     * @param theme    active theme (nullable)
     * @param nextPage the 1-based page number to fetch next
     * @param pageSize the per-page size in effect (echoed as {@code :N} when non-default)
     * @param filter   the raw filter string (nullable) minus any page token
     * @return footer component
     */
    public static Component footer(Theme theme, int nextPage, int pageSize, String filter) {
        String pageTok = pageSize == 10
                ? Integer.toString(nextPage)
                : nextPage + ":" + pageSize;
        String f = filter == null ? "" : filter.trim();
        String cmd = f.isEmpty()
                ? "/vg lookup " + pageTok
                : "/vg lookup " + pageTok + " " + f;
        MutableComponent c = ChatRenderer.muted(theme, "  \u00bb Next page: ");
        c.append(ChatRenderer.secondary(theme, cmd));
        return c;
    }

    private static String safe(String s)        { return s == null ? "?" : s; }
    private static String safeName(String s)    { return s == null ? "#unknown" : s; }

    private static String verb(Action a) {
        return switch (a.type()) {
            case BLOCK_BREAK, BURN, FADE, BUCKET_FILL, LEAVES_DECAY,
                 PISTON_RETRACT, HANGING_BREAK              -> "broke";
            case BLOCK_PLACE, FORM, SPREAD, IGNITE, BUCKET_EMPTY,
                 PISTON_EXTEND, HANGING_PLACE, FLUID_FLOW    -> "placed";
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
