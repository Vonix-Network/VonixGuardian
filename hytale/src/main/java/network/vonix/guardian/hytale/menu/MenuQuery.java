/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.hytale.spi.PlayerDirectory;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParseException;
import network.vonix.guardian.core.query.QueryParser;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query preparation for the menu. {@code CommandRuntime.prepare} is
 * package-private, so this copies that scoping: default rollback radius,
 * max radius, world scope, and {@code viewothers}.
 */
final class MenuQuery {

    static final int DEFAULT_ROLLBACK_RADIUS = 10;
    static final int UI_PAGE_CAP = 8;

    private static final Pattern PAGE_TOKEN = Pattern.compile("^(\\d+)(?::(\\d+))?$");

    private final QueryParser parser = new QueryParser();

    Prepared prepare(Guardian guardian, MenuHost host, MenuHost.Actor actor,
                     String raw, Kind kind, boolean forcePreview) {
        String cleaned = stripControls(raw);
        if (forcePreview) {
            cleaned = cleaned.isEmpty() ? "#preview" : cleaned + " #preview";
        }
        QueryFilter filter;
        try {
            QueryParser.QueryParseContext ctx = null;
            if (actor.x() != null && actor.y() != null && actor.z() != null) {
                ctx = new QueryParser.QueryParseContext(actor.x(), actor.y(), actor.z(), actor.uuid());
            }
            filter = parser.parse(cleaned, ctx);
        } catch (QueryParseException ex) {
            return Prepared.error(ex.getMessage());
        }
        if (filter.worldEditPlayer() != null) {
            return Prepared.error("WorldEdit selections (r:#we) are not available on Hytale. Refusing.");
        }
        if (kind == Kind.ROLLBACK || kind == Kind.RESTORE) {
            filter = withDefaultRadius(filter, actor);
        }
        try {
            int maxRadius = guardian.config().lookup().maxRadius();
            filter = QueryParser.enforceMaxRadius(filter, maxRadius);
        } catch (QueryParseException ex) {
            return Prepared.error(ex.getMessage());
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Lookup radius limit unavailable", ex);
            return Prepared.error("Lookup radius limit is unavailable. Refusing.");
        }
        boolean explicitWorld = filter.worldSel() != null;
        if (actor.player() && actor.worldLookupFailed()
                && !(kind == Kind.LOOKUP && explicitWorld)) {
            return Prepared.error("World id lookup failed. Refusing an unscoped query.");
        }
        if (actor.player() && actor.worldId() != null) {
            filter = filter.withDefaultWorld(actor.worldId());
        }
        if (actor.player() && filter.worldSel() == null) {
            return Prepared.error("Your world id is not available, so this query would not be scoped. Refusing.");
        }
        if ((kind == Kind.ROLLBACK || kind == Kind.RESTORE)
                && actor.player()
                && filter.radius() == null
                && filter.worldEditPlayer() == null
                && (filter.worldSel() == null || !filter.worldSel().global())) {
            return Prepared.error("Rollback needs your position or an explicit r:<radius>. Refusing.");
        }
        String scopeError = scopeToSelf(host, actor, filter);
        if (scopeError != null) {
            return Prepared.error(scopeError);
        }
        QueryFilter scoped = scopedFilter(host, actor, filter);
        if (scoped == null) {
            return Prepared.error("Your identity is unavailable, so this query would not be scoped to you. Refusing.");
        }
        return Prepared.ok(scoped);
    }

    int pageSize(Guardian guardian) {
        int size = guardian.config().lookup().defaultPageSize();
        if (size < 1) {
            return 1;
        }
        return Math.min(UI_PAGE_CAP, Math.min(50, size));
    }

    static String stripControls(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (String token : raw.trim().split("\\s+")) {
            String lower = token.toLowerCase(Locale.ROOT);
            if ("#preview".equals(lower) || "--confirm".equals(lower) || "--confirm=true".equals(lower)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }

    static PageSplit splitPage(String raw, int defaultPerPage) {
        int page = 1;
        int perPage = defaultPerPage;
        String filter = raw == null ? "" : raw;
        String trimmed = filter.trim();
        if (!trimmed.isEmpty()) {
            int space = trimmed.indexOf(' ');
            String first = space < 0 ? trimmed : trimmed.substring(0, space);
            Matcher matcher = PAGE_TOKEN.matcher(first);
            if (matcher.matches()) {
                try {
                    page = Math.max(1, Integer.parseInt(matcher.group(1)));
                    if (matcher.group(2) != null) {
                        perPage = Math.max(1, Math.min(UI_PAGE_CAP, Integer.parseInt(matcher.group(2))));
                    }
                } catch (NumberFormatException ignored) {
                    // An oversized token keeps the defaults and is still removed.
                }
                filter = space < 0 ? "" : trimmed.substring(space + 1);
            }
        }
        return new PageSplit(page, perPage, filter);
    }

    private String scopeToSelf(MenuHost host, MenuHost.Actor actor, QueryFilter filter) {
        if (!actor.player()) {
            return null;
        }
        Boolean allowed = viewOthers(host, actor);
        if (allowed == null) {
            return "viewothers check failed. Refusing to widen the query.";
        }
        if (allowed || filter.users().isEmpty()) {
            return null;
        }
        for (QueryFilter.UserSel user : filter.users()) {
            if (!isSelf(actor, user)) {
                return "You need " + MenuHost.VIEW_OTHERS_NODE + " to target another user.";
            }
        }
        return null;
    }

    private QueryFilter scopedFilter(MenuHost host, MenuHost.Actor actor, QueryFilter filter) {
        if (!actor.player() || !filter.users().isEmpty()) {
            return filter;
        }
        Boolean allowed = viewOthers(host, actor);
        if (allowed == null) {
            return null;
        }
        if (allowed) {
            return filter;
        }
        if (actor.uuid() == null || actor.name().isBlank()) {
            return null;
        }
        return new QueryFilter(
                List.of(new QueryFilter.UserSel(actor.uuid(), actor.name(), false)),
                filter.sinceMillis(), filter.untilMillis(), filter.radius(), filter.worldSel(),
                filter.centerX(), filter.centerY(), filter.centerZ(),
                filter.actions(), filter.include(), filter.exclude(),
                filter.rolledBack(), filter.countOnly(), filter.preview(), filter.verbose(),
                filter.silent(), filter.optimize(), filter.worldEditPlayer(), filter.actionIds());
    }

    private Boolean viewOthers(MenuHost host, MenuHost.Actor actor) {
        if (actor.uuid() == null) {
            return null;
        }
        PlayerDirectory players;
        try {
            players = host.services().players();
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "Player directory unavailable during viewothers", ex);
            return null;
        }
        if (players == null) {
            return null;
        }
        try {
            return players.hasPermission(actor.uuid(), MenuHost.VIEW_OTHERS_NODE);
        } catch (RuntimeException ex) {
            host.logger().warn(Guardian.MARKER, "viewothers check failed", ex);
            return null;
        }
    }

    private static boolean isSelf(MenuHost.Actor actor, QueryFilter.UserSel user) {
        if (user.isSentinel()) {
            return false;
        }
        if (user.uuid() != null && user.uuid().equals(actor.uuid())) {
            return true;
        }
        return user.name() != null && !actor.name().isBlank()
                && user.name().equalsIgnoreCase(actor.name());
    }

    private static QueryFilter withDefaultRadius(QueryFilter filter, MenuHost.Actor actor) {
        if (filter.radius() != null || filter.worldSel() != null || filter.worldEditPlayer() != null) {
            return filter;
        }
        if (actor.x() == null || actor.y() == null || actor.z() == null) {
            return filter;
        }
        return new QueryFilter(
                filter.users(), filter.sinceMillis(), filter.untilMillis(),
                DEFAULT_ROLLBACK_RADIUS,
                filter.worldSel(),
                filter.centerX() != null ? filter.centerX() : actor.x(),
                filter.centerY() != null ? filter.centerY() : actor.y(),
                filter.centerZ() != null ? filter.centerZ() : actor.z(),
                filter.actions(), filter.include(), filter.exclude(),
                filter.rolledBack(), filter.countOnly(), filter.preview(), filter.verbose(),
                filter.silent(), filter.optimize(), filter.worldEditPlayer(), filter.actionIds());
    }

    enum Kind {
        LOOKUP, ROLLBACK, RESTORE, PURGE
    }

    record Prepared(QueryFilter filter, String error) {
        static Prepared ok(QueryFilter filter) {
            return new Prepared(filter, null);
        }

        static Prepared error(String error) {
            return new Prepared(null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    record PageSplit(int page, int perPage, String filter) {
    }
}
