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
        Map<PermissionNode, Boolean> granted = new EnumMap<>(PermissionNode.class);
        List<Action> out = new ArrayList<>(rows.size());
        for (Action a : rows) {
            if (isVisibleType(resolver, uuid, family, a.type(), granted)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * Counts only rows visible under the same child-permission rules as
     * {@link #filter(PermissionResolver, UUID, PermissionNode, List)}.
     * Counts stay database-side: one bounded SQL COUNT of the permitted
     * action types, so unrestricted lookups do not issue one COUNT per
     * child-permission bucket. Denied child buckets are omitted from the
     * {@code a.type IN (...)} predicate; no raw rows are materialized.
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

        EnumSet<ActionType> visibleTypes = visibleActionTypes(resolver, uuid, family, filter);
        if (visibleTypes.isEmpty()) {
            return 0L;
        }
        return dao.count(restrictToVisibleTypes(filter, visibleTypes));
    }

    /**
     * Fetches a page in visible-row coordinates. Permitted action types are
     * applied SQL-side. Page 2+ uses a bounded first batch followed by
     * {@code (ts,id)} keyset continuation instead of a deep OFFSET; older
     * implementations fall back to the bounded OFFSET path. Denied types
     * still cannot occupy visible slots. Scanning is bounded; a skip at or
     * beyond {@value #MAX_RAW_ROWS_SCANNED} fails closed.
     */
    public static VisiblePage visiblePage(
            GuardianDao dao,
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            QueryFilter filter,
            int page,
            int pageSize) throws Exception {
        return visiblePage(dao, resolver, uuid, family, filter, page, pageSize, false);
    }

    /**
     * Fetches a visible page and, when requested, one extra visible row.
     *
     * <p>The extra row is a page-plus-one probe: it tells the caller whether a
     * next page exists without running a full-table COUNT first. The probe is
     * performed in the same bounded worker/DAO path as the page itself, and the
     * extra row is never exposed to the formatter.</p>
     */
    public static VisiblePage visiblePage(
            GuardianDao dao,
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            QueryFilter filter,
            int page,
            int pageSize,
            boolean prefetchNext) throws Exception {
        if (dao == null || resolver == null || family == null || filter == null) {
            throw new IllegalArgumentException("dao, resolver, family, and filter are required");
        }
        if (page < 1 || pageSize < 1) {
            throw new IllegalArgumentException("page and pageSize must be > 0");
        }

        EnumSet<ActionType> visibleTypes = null;
        QueryFilter pageFilter = filter;
        if (uuid != null) {
            visibleTypes = visibleActionTypes(resolver, uuid, family, filter);
            if (visibleTypes.isEmpty()) {
                return new VisiblePage(List.of(), true, 0, false);
            }
            pageFilter = restrictToVisibleTypes(filter, visibleTypes);
        }

        long visibleSkip = (long) (page - 1) * pageSize;
        // SQL-side type restriction means denied types never occupy result
        // slots. For page 1 the visible skip is therefore a raw skip. For
        // deeper pages, walk from the first batch with a (ts,id) keyset so a
        // large page number does not force the database to discard every
        // preceding row through OFFSET. Older/mock DAOs may return null from
        // the keyset method; restart once through the bounded OFFSET fallback.
        if (visibleSkip >= MAX_RAW_ROWS_SCANNED || visibleSkip > Integer.MAX_VALUE) {
            return new VisiblePage(List.of(), false, 0, false);
        }
        long targetLong = (long) pageSize + (prefetchNext ? 1L : 0L);
        int targetRows = targetLong >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) targetLong;
        // There is no caller-supplied cursor in the page-number API. Use one
        // zero-origin keyset anchor only for page 2; deeper pages must use a
        // single bounded OFFSET rather than repeatedly scanning from row zero.
        boolean seekMode = page == 2;
        boolean keysetUnavailable = false;
        long remainingSkip = seekMode ? visibleSkip : 0L;
        int rawOffset = seekMode ? 0 : (int) visibleSkip;
        Action cursor = null;
        int scanned = seekMode ? 0 : rawOffset;
        List<Action> out = new ArrayList<>(Math.min(targetRows, RAW_PAGE_SIZE));
        while (scanned < MAX_RAW_ROWS_SCANNED && out.size() < targetRows) {
            int stillNeed = targetRows - out.size();
            int fetchLimit = Math.min(RAW_PAGE_SIZE, Math.min(stillNeed, MAX_RAW_ROWS_SCANNED - scanned));
            if (fetchLimit < 1) {
                break;
            }
            // Display projection: lookup formatting never reads NBT payloads.
            // Rollback/restore keep using GuardianDao.queryPage (full columns).
            GuardianDao.QueryPage rawPage;
            if (seekMode && !keysetUnavailable && cursor != null) {
                rawPage = dao.queryPageForDisplayAfter(
                        pageFilter, cursor.timestamp(), cursor.id(), fetchLimit);
                if (rawPage == null) {
                    // Contract-safe fallback for non-JDBC implementations.
                    keysetUnavailable = true;
                    remainingSkip = 0L;
                    rawOffset = (int) visibleSkip;
                    cursor = null;
                    out.clear();
                    scanned = (int) visibleSkip;
                    continue;
                }
            } else {
                rawPage = dao.queryPageForDisplay(pageFilter, rawOffset, fetchLimit);
            }
            List<Action> raw = rawPage.rows();
            if (raw.isEmpty()) {
                return pageResult(out, pageSize, true, scanned);
            }
            // A DAO cap is ambiguous when the caller did not request a
            // page-plus-one probe. Preserve the old fail-closed behavior. With
            // the probe enabled, a filled target is enough to prove hasNext.
            if (rawPage.truncated() && !prefetchNext) {
                scanned += raw.size();
                return pageResult(out, pageSize, false, scanned);
            }
            scanned += raw.size();
            if (seekMode && !keysetUnavailable) {
                cursor = raw.get(raw.size() - 1);
            } else {
                if (rawOffset > Integer.MAX_VALUE - raw.size()) {
                    return pageResult(out, pageSize, false, scanned);
                }
                rawOffset += raw.size();
            }

            for (Action action : raw) {
                if (visibleTypes != null && !visibleTypes.contains(action.type())) {
                    continue;
                }
                if (remainingSkip > 0L) {
                    remainingSkip--;
                    continue;
                }
                out.add(action);
                if (out.size() == targetRows) {
                    return pageResult(out, pageSize, true, scanned);
                }
            }
            if (rawPage.truncated()) {
                // The capped response did not supply enough visible rows to
                // prove the requested page-plus-one result.
                return pageResult(out, pageSize, false, scanned);
            }
        }
        return pageResult(out, pageSize, false, scanned);
    }

    private static VisiblePage pageResult(List<Action> rows, int pageSize, boolean complete, int scanned) {
        int visibleCount = Math.min(pageSize, rows.size());
        boolean hasNext = complete && rows.size() > pageSize;
        return new VisiblePage(rows.subList(0, visibleCount), complete, scanned, hasNext);
    }

    /** Result of a bounded visible-row page scan. */
    public record VisiblePage(List<Action> rows, boolean complete, int rawRowsScanned, boolean hasNext) {
        public VisiblePage(List<Action> rows, boolean complete, int rawRowsScanned) {
            this(rows, complete, rawRowsScanned, false);
        }

        public VisiblePage {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * Action types the viewer may see under {@code family}. Console callers
     * never reach this helper. Child probes are cached so each distinct
     * permission bucket is resolved once.
     */
    private static EnumSet<ActionType> visibleActionTypes(
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            QueryFilter filter) {
        EnumSet<ActionType> visible = EnumSet.noneOf(ActionType.class);
        Map<PermissionNode, Boolean> granted = new EnumMap<>(PermissionNode.class);
        if (filter.actions().isEmpty()) {
            for (ActionType type : ActionType.values()) {
                if (isVisibleType(resolver, uuid, family, type, granted)) {
                    visible.add(type);
                }
            }
            return visible;
        }
        for (QueryFilter.ActionSelect select : filter.actions()) {
            if (select.type() == null) continue;
            if (isVisibleType(resolver, uuid, family, select.type(), granted)) {
                visible.add(select.type());
            }
        }
        return visible;
    }

    private static boolean isVisibleType(
            PermissionResolver resolver,
            UUID uuid,
            PermissionNode family,
            ActionType type,
            Map<PermissionNode, Boolean> granted) {
        PermissionNode child = PermissionNode.childForAction(family, type);
        if (child == family) {
            return true;
        }
        return granted.computeIfAbsent(child, node -> resolver.has(uuid, node));
    }

    /**
     * Narrow {@code source} to {@code visible} types in one SQL predicate.
     * Unrestricted filters that already permit every {@link ActionType} are
     * left unchanged so COUNT/SELECT can keep the original plan (no
     * {@code a.type IN} clause).
     */
    private static QueryFilter restrictToVisibleTypes(QueryFilter source, EnumSet<ActionType> visible) {
        if (source.actions().isEmpty()) {
            if (visible.size() == ActionType.values().length) {
                return source;
            }
            return withActionTypes(source, visible);
        }
        List<QueryFilter.ActionSelect> kept = new ArrayList<>(source.actions().size());
        boolean dropped = false;
        for (QueryFilter.ActionSelect select : source.actions()) {
            if (select.type() != null && visible.contains(select.type())) {
                kept.add(select);
            } else {
                dropped = true;
            }
        }
        if (!dropped) {
            return source;
        }
        return copyWithActions(source, kept);
    }

    private static QueryFilter withActionTypes(QueryFilter source, EnumSet<ActionType> types) {
        List<QueryFilter.ActionSelect> actions = new ArrayList<>(types.size());
        for (ActionType type : types) {
            actions.add(new QueryFilter.ActionSelect(type, QueryFilter.ActionSelect.Sign.ANY));
        }
        return copyWithActions(source, actions);
    }

    private static QueryFilter copyWithActions(QueryFilter source, List<QueryFilter.ActionSelect> actions) {
        return new QueryFilter(
                source.users(), source.sinceMillis(), source.untilMillis(), source.radius(),
                source.worldSel(), source.centerX(), source.centerY(), source.centerZ(),
                actions, source.include(), source.exclude(), source.rolledBack(),
                source.countOnly(), source.preview(), source.verbose(), source.silent(),
                source.optimize(), source.worldEditPlayer(), source.actionIds());
    }
}
