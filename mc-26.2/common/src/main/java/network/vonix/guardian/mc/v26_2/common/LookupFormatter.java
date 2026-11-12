/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_2.common;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.command.LookupText;
import network.vonix.guardian.core.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Paints a lookup page. The words come from core so every loader says the
 * same thing. This class only applies the theme and the click or hover.
 */
public final class LookupFormatter {

    private LookupFormatter() {
    }

    public static MutableComponent line(Theme theme, Action action, long now) {
        return line(theme, action, now, false);
    }

    public static MutableComponent line(Theme theme, Action action, long now, boolean verbose) {
        MutableComponent row = ChatRenderer.plain("");
        for (LookupText.Piece piece : LookupText.line(action, now, verbose)) {
            row.append(ChatRenderer.interactive(theme, piece.role(), piece.text(),
                    piece.hover(), piece.suggest(), piece.copy()));
        }
        return row;
    }

    public static Component header(Theme theme, long total, int page, int pages) {
        String noun = total == 1 ? "result" : "results";
        return ChatRenderer.interactive(theme, "header",
                "Lookup: " + total + " " + noun
                        + " (page " + Math.max(1, page) + "/" + Math.max(1, pages) + ")",
                null, null, null);
    }

    public static List<Component> page(Theme theme, List<Action> rows, long total,
                                       int page, int pageSize, long now) {
        return page(theme, rows, total, page, pageSize, now, null);
    }

    public static List<Component> page(Theme theme, List<Action> rows, long total,
                                       int page, int pageSize, long now, String filter) {
        int pages = (int) Math.max(1L, (total + pageSize - 1) / Math.max(1, pageSize));
        int curr = Math.max(1, page);
        List<Component> out = new ArrayList<>(rows.size() + 2);
        out.add(header(theme, total, curr, pages));
        for (Action action : rows) {
            out.add(line(theme, action, now, false));
        }
        if (curr < pages) {
            out.add(footer(theme, curr + 1, pageSize, filter));
        }
        return out;
    }

    public static List<Component> page(Theme theme, List<Action> rows,
                                       int page, int pageSize, long now,
                                       String filter, boolean hasNext) {
        return page(theme, rows, page, pageSize, now, filter, hasNext, false);
    }

    public static List<Component> page(Theme theme, List<Action> rows,
                                       int page, int pageSize, long now,
                                       String filter, boolean hasNext, boolean verbose) {
        int curr = Math.max(1, page);
        List<Component> out = new ArrayList<>(rows.size() + 2);
        String title = "Lookup page " + curr + (hasNext ? " — more below" : "");
        out.add(ChatRenderer.interactive(theme, "header", title, null, null, null));
        for (Action action : rows) {
            out.add(line(theme, action, now, verbose));
        }
        if (hasNext) {
            out.add(footer(theme, curr + 1, pageSize, filter));
        }
        return out;
    }

    public static Component footer(Theme theme, int nextPage, int pageSize, String filter) {
        String pageTok = pageSize == 10
                ? Integer.toString(nextPage)
                : nextPage + ":" + pageSize;
        String rest = filter == null ? "" : filter.trim();
        String cmd = rest.isEmpty()
                ? "/vg lookup " + pageTok
                : "/vg lookup " + pageTok + " " + rest;
        return ChatRenderer.interactive(theme, "meta", "  Next: " + cmd,
                "Click to put the next page in the chat box", cmd, null);
    }
}
