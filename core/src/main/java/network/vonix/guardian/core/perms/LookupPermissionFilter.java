package network.vonix.guardian.core.perms;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

/**
 * Filters a raw list of {@link Action}s down to just the rows the source is
 * permitted to see under CoreProtect-style child-node semantics.
 *
 * <p>The caller has already passed the coarse family check (e.g.
 * {@link PermissionNode#LOOKUP}). Per row we compute the CoreProtect child
 * node via {@link PermissionNode#childForAction(PermissionNode, network.vonix.guardian.core.action.ActionType)}
 * &mdash; if the child is <em>identical</em> to the family node (fall-open), the
 * row survives; otherwise the source must additionally hold the child node.</p>
 *
 * <p>Filtering is silent: dropped rows are not surfaced to the caller. This
 * matches CoreProtect's contract of "hide what you can't see" rather than
 * throwing a permission error mid-page.</p>
 *
 * @since 1.1.7 (W3-B7)
 */
public final class LookupPermissionFilter {

    private static final int RAW_PAGE_SIZE = 256;
    private static final int MAX_RAW_ROWS_SCANNED = 100_000;

    private LookupPermissionFilter() {
        // utility
    }

    /**
     * Filter {@code rows} down to those the given source may see.
     *
     * @param resolver live resolver (must not be {@code null})
     * @param uuid     player UUID; {@code null} = console (bypasses all child checks)
     * @param family   coarse family node ({@link PermissionNode#LOOKUP},
     *                 {@link PermissionNode#ROLLBACK}, {@link PermissionNode#RESTORE})
     * @param rows     raw result rows (must not be {@code null}; may be empty)
     * @return a new list containing only rows whose child node is granted
     */
    public static List<Action> filter(
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            List<Action> rows) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        if (family == null) {
            throw new IllegalArgumentException("family must not be null");
        }
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        // Console bypass: consoles hold the family node by default op level;
        // don't run n further probes.
        if (uuid == null) {
            return new ArrayList<>(rows);
        }
        List<Action> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            PermissionNode child = PermissionNode.childForAction(family, a.type());
            if (child == family) {
                // Fall-open — no scoped child exists, family perm is enough.
                out.add(a);
                continue;
            }
            if (resolver.has(uuid, child)) {
                out.add(a);
            }
            // else: silently drop
        }
        return out;
    }

    /**
     * Counts only rows visible under the same child-permission rules as
     * {@link #filter(PermissionResolver, UUID, PermissionNode, List)}.
     * Counts stay database-side: one bounded SQL COUNT per permitted child
     * bucket, with action types partitioned so no raw rows are materialized.
     */
    public static long countVisible(
            GuardianDao dao,
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            QueryFilter filter) throws Exception {
        if (dao == null || resolver == null || family == null || filter == null) {
            throw new IllegalArgumentException("dao, resolver, family, and filter are required");
        }
        if (uuid == null) {
            return dao.count(filter);
        }

        Map<PermissionNode, EnumSet<ActionType>> buckets = new EnumMap<>(PermissionNode.class);
        if (filter.actions().isEmpty()) {
            for (ActionType type : ActionType.values()) {
                buckets.computeIfAbsent(PermissionNode.childForAction(family, type),
                        ignored -> EnumSet.noneOf(ActionType.class)).add(type);
            }
        } else {
            for (QueryFilter.ActionSelect select : filter.actions()) {
                if (select.type() == null) continue;
                buckets.computeIfAbsent(PermissionNode.childForAction(family, select.type()),
                        ignored -> EnumSet.noneOf(ActionType.class)).add(select.type());
            }
        }

        long visible = 0L;
        for (Map.Entry<PermissionNode, EnumSet<ActionType>> entry : buckets.entrySet()) {
            PermissionNode child = entry.getKey();
            if (child != family && !resolver.has(uuid, child)) continue;
            visible = Math.addExact(visible, dao.count(withActionTypes(filter, entry.getValue())));
        }
        return visible;
    }

    /**
     * Fetches a page in visible-row coordinates. Raw DAO pages are streamed
     * from the beginning of the result set until the requested visible offset
     * and page are satisfied, so denied rows never consume visible slots.
     * Scanning is bounded; any rows returned after the bound are deliberately
     * not exposed by this call.
     */
    public static VisiblePage visiblePage(
            GuardianDao dao,
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            QueryFilter filter,
            int page,
            int pageSize) throws Exception {
        if (dao == null || resolver == null || family == null || filter == null) {
            throw new IllegalArgumentException("dao, resolver, family, and filter are required");
        }
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("page and pageSize must be > 0");
        }

        long visibleSkip = (long) (page - 1) * pageSize;
        int rawOffset = 0;
        int scanned = 0;
        List<Action> out = new ArrayList<>(pageSize);
        while (scanned < MAX_RAW_ROWS_SCANNED && out.size() < pageSize) {
            int fetchLimit = Math.min(RAW_PAGE_SIZE, MAX_RAW_ROWS_SCANNED - scanned);
            GuardianDao.QueryPage rawPage = dao.queryPage(filter, rawOffset, fetchLimit);
            List<Action> raw = rawPage.rows();
            if (raw.isEmpty()) {
                return new VisiblePage(out, true, scanned);
            }
            // DAO result-cap truncation is not EOF. Fail closed so a permission
            // filter cannot present a partial visible page as complete.
            if (rawPage.truncated()) {
                scanned += raw.size();
                return new VisiblePage(out, false, scanned);
            }
            scanned += raw.size();
            if (rawOffset > Integer.MAX_VALUE - raw.size()) {
                return new VisiblePage(out, false, scanned);
            }
            rawOffset += raw.size();

            for (Action action : filter(resolver, uuid, family, raw)) {
                if (visibleSkip > 0L) {
                    visibleSkip--;
                } else {
                    out.add(action);
                    if (out.size() == pageSize) {
                        return new VisiblePage(out, true, scanned);
                    }
                }
            }
        }
        return new VisiblePage(out, false, scanned);
    }

    /** Result of a bounded visible-row page scan. */
    public record VisiblePage(List<Action> rows, boolean complete, int rawRowsScanned) {
        public VisiblePage {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    private static QueryFilter withActionTypes(QueryFilter source, EnumSet<ActionType> types) {
        List<QueryFilter.ActionSelect> actions = new ArrayList<>(types.size());
        for (ActionType type : types) {
            actions.add(new QueryFilter.ActionSelect(type, QueryFilter.ActionSelect.Sign.ANY));
        }
        return new QueryFilter(
                source.users(), source.sinceMillis(), source.untilMillis(), source.radius(),
                source.worldSel(), source.centerX(), source.centerY(), source.centerZ(),
                actions, source.include(), source.exclude(), source.rolledBack(),
                source.countOnly(), source.preview(), source.verbose(), source.silent(),
                source.optimize(), source.worldEditPlayer(), source.actionIds());
    }
}
