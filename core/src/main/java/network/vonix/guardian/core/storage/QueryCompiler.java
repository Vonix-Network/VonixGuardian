package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.WorldEditRegionResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles a {@link QueryFilter} into a parameterised SQL statement against the
 * {@code vg_actions} table joined with {@code vg_users} and {@code vg_worlds}.
 *
 * <p><b>Safety:</b> only column names from the {@link #COLUMN_WHITELIST} are ever
 * concatenated into the SQL string. Every value the user supplied flows through positional
 * {@code ?} binds — no string interpolation of user input ever happens.
 */
public final class QueryCompiler {

    private static final int PLACEHOLDER_CACHE_MAX = 64;
    private static final String[] PLACEHOLDER_CACHE = buildPlaceholderCache();

    /**
     * Columns we will ever splice into SQL by name (e.g. for ORDER BY, projection).
     * Anything not in this set MUST be bound, not concatenated.
     */
    public static final Set<String> COLUMN_WHITELIST = Set.of(
        "id", "ts", "type", "user_id", "world_id", "x", "y", "z",
        "target", "meta", "amount", "rolled_back", "source_tag",
        "sign_side", "sign_dye_color", "sign_waxed",
        "old_block_state", "new_block_state", "block_entity_nbt", "item_nbt", "entity_nbt",
        "uuid", "name", "world_key"
    );

    /** The canonical SELECT projection — kept in one place so both query() and count() agree. */
    public static final String SELECT_PROJECTION =
        "a.id, a.ts, a.type, u.uuid, u.name, w.world_key, "
      + "a.x, a.y, a.z, a.target, a.meta, a.amount, a.rolled_back, a.source_tag, "
      + "a.sign_side, a.sign_dye_color, a.sign_waxed, "
      + "a.old_block_state, a.new_block_state, a.block_entity_nbt, a.item_nbt, a.entity_nbt";

    private static final String FROM_JOIN =
        " FROM vg_actions a "
      + "JOIN vg_users  u ON u.id = a.user_id "
      + "JOIN vg_worlds w ON w.id = a.world_id";

    private QueryCompiler() {}

    /** Result of compilation: SQL string + ordered bind values. */
    public record Compiled(String sql, List<Object> binds) {}

    /** Build a SELECT for paged retrieval. */
    public static Compiled compileSelect(QueryFilter filter, int offset, int limit) {
        StringBuilder sb = new StringBuilder("SELECT ").append(SELECT_PROJECTION).append(FROM_JOIN);
        List<Object> binds = new ArrayList<>();
        appendWhere(sb, filter, binds);
        sb.append(" ORDER BY a.ts DESC, a.id DESC LIMIT ? OFFSET ?");
        binds.add(limit);
        binds.add(offset);
        return new Compiled(sb.toString(), binds);
    }

    /** Build a COUNT(*) for the same filter. */
    public static Compiled compileCount(QueryFilter filter) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*)").append(FROM_JOIN);
        List<Object> binds = new ArrayList<>();
        appendWhere(sb, filter, binds);
        return new Compiled(sb.toString(), binds);
    }

    /** Build a DELETE driven by the same filter (used by purge). */
    public static Compiled compileDelete(QueryFilter filter) {
        // We can't directly DELETE FROM a join on every backend (MySQL ok, SQLite + PG vary).
        // Use a portable subquery on the id.
        StringBuilder sb = new StringBuilder(
            "DELETE FROM vg_actions WHERE id IN (SELECT a.id").append(FROM_JOIN);
        List<Object> binds = new ArrayList<>();
        appendWhere(sb, filter, binds);
        sb.append(")");
        return new Compiled(sb.toString(), binds);
    }

    // ---------------------------------------------------------------------

    private static void appendWhere(StringBuilder sb, QueryFilter f, List<Object> binds) {
        List<String> clauses = new ArrayList<>();

        // Explicit action-id set (used by /vg undo to replay the exact rows from the popped operation).
        if (f.actionIds() != null && !f.actionIds().isEmpty()) {
            clauses.add("a.id IN (" + placeholders(f.actionIds().size()) + ")");
            binds.addAll(f.actionIds());
        }

        // users — match either uuid (when known) or name (when sentinel or name-only).
        if (f.users() != null && !f.users().isEmpty()) {
            List<String> sub = new ArrayList<>();
            for (QueryFilter.UserSel s : f.users()) {
                if (s.uuid() != null) {
                    sub.add("u.uuid = ?");
                    binds.add(s.uuid().toString());
                } else if (s.name() != null) {
                    sub.add("u.name = ?");
                    binds.add(s.name());
                }
            }
            if (!sub.isEmpty()) {
                clauses.add("(" + String.join(" OR ", sub) + ")");
            }
        }

        // time
        if (f.sinceMillis() != null) {
            clauses.add("a.ts >= ?");
            binds.add(f.sinceMillis());
        }
        if (f.untilMillis() != null) {
            clauses.add("a.ts <= ?");
            binds.add(f.untilMillis());
        }

        // world / radius
        if (f.worldSel() != null && !f.worldSel().global() && f.worldSel().worldKey() != null) {
            clauses.add("w.world_key = ?");
            binds.add(f.worldSel().worldKey());
        }
        // radius: -1 means #global (no spatial filter); positive value + center -> bounding box
        Integer r = f.radius();
        if (r != null && r >= 0 && f.centerX() != null && f.centerZ() != null) {
            clauses.add("a.x BETWEEN ? AND ?");
            binds.add(f.centerX() - r);
            binds.add(f.centerX() + r);
            clauses.add("a.z BETWEEN ? AND ?");
            binds.add(f.centerZ() - r);
            binds.add(f.centerZ() + r);
            if (f.centerY() != null) {
                clauses.add("a.y BETWEEN ? AND ?");
                binds.add(f.centerY() - r);
                binds.add(f.centerY() + r);
            }
        }

        // WorldEdit selection AABB (r:#we / r:#worldedit). Resolved on-demand
        // at compile time via reflection; if WE is not installed or the player
        // has no active selection, we emit an impossible predicate so the
        // query matches zero rows. The upper layer may use
        // {@link WorldEditRegionResolver#resolveSelection(java.util.UUID)}
        // itself to surface a user-facing message
        // ({@code query.error.worldedit_not_installed} / {@code
        // query.error.worldedit_no_selection}) before invoking the DAO.
        if (f.worldEditPlayer() != null) {
            java.util.Optional<WorldEditRegionResolver.Box> box =
                WorldEditRegionResolver.resolveSelection(f.worldEditPlayer());
            if (box.isPresent()) {
                WorldEditRegionResolver.Box b = box.get();
                clauses.add("a.x BETWEEN ? AND ?");
                binds.add(b.minX()); binds.add(b.maxX());
                clauses.add("a.y BETWEEN ? AND ?");
                binds.add(b.minY()); binds.add(b.maxY());
                clauses.add("a.z BETWEEN ? AND ?");
                binds.add(b.minZ()); binds.add(b.maxZ());
            } else {
                // No selection / WE missing — match zero rows.
                clauses.add("1 = 0");
            }
        }

        // action types
        if (f.actions() != null && !f.actions().isEmpty()) {
            Set<Integer> ids = new LinkedHashSet<>();
            for (QueryFilter.ActionSelect a : f.actions()) {
                ActionType t = a.type();
                if (t != null) {
                    ids.add(t.id());
                }
            }
            if (!ids.isEmpty()) {
                clauses.add("a.type IN (" + placeholders(ids.size()) + ")");
                binds.addAll(ids);
            }
        }

        // include / exclude
        if (f.include() != null && !f.include().isEmpty()) {
            appendIncludeClause(clauses, binds, f.include());
        }
        if (f.exclude() != null && !f.exclude().isEmpty()) {
            appendExcludeClauses(clauses, binds, f.exclude());
        }

        // rolled_back SQL-side filter
        if (f.rolledBack() != null) {
            clauses.add("a.rolled_back = ?");
            binds.add(f.rolledBack() ? 1 : 0);
        }

        if (!clauses.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", clauses));
        }
    }

    private static void appendIncludeClause(List<String> clauses, List<Object> binds, List<String> values) {
        String in = placeholders(values.size());
        StringBuilder clause = new StringBuilder("(a.target IN (").append(in).append(')');
        binds.addAll(values);

        clause.append(" OR (a.type = ? AND a.meta IN (").append(in).append("))");
        binds.add(ActionType.ENTITY_CHANGE_BLOCK.id());
        binds.addAll(values);

        clause.append(" OR (a.type = ? AND (").append(explosionLikeSql(values.size())).append("))");
        binds.add(ActionType.EXPLOSION.id());
        bindExplosionLikePatterns(binds, values);

        clause.append(')');
        clauses.add(clause.toString());
    }

    private static void appendExcludeClauses(List<String> clauses, List<Object> binds, List<String> values) {
        String in = placeholders(values.size());
        clauses.add("a.target NOT IN (" + in + ")");
        binds.addAll(values);

        clauses.add("NOT (a.type = ? AND a.meta IN (" + in + "))");
        binds.add(ActionType.ENTITY_CHANGE_BLOCK.id());
        binds.addAll(values);

        clauses.add("NOT (a.type = ? AND (" + explosionLikeSql(values.size()) + "))");
        binds.add(ActionType.EXPLOSION.id());
        bindExplosionLikePatterns(binds, values);
    }

    private static String explosionLikeSql(int valueCount) {
        List<String> parts = new ArrayList<>(valueCount * 3);
        for (int i = 0; i < valueCount; i++) {
            parts.add("a.target LIKE ?");
            parts.add("a.target LIKE ?");
            parts.add("a.target LIKE ?");
        }
        return String.join(" OR ", parts);
    }

    private static void bindExplosionLikePatterns(List<Object> binds, List<String> values) {
        for (String v : values) {
            binds.add("%=" + v + ",%");
            binds.add("%=" + v + "|%");
            binds.add("%=" + v);
        }
    }

    private static String placeholders(int n) {
        if (n >= 0 && n <= PLACEHOLDER_CACHE_MAX) {
            return PLACEHOLDER_CACHE[n];
        }
        return buildPlaceholders(n);
    }

    private static String[] buildPlaceholderCache() {
        String[] cache = new String[PLACEHOLDER_CACHE_MAX + 1];
        for (int i = 0; i < cache.length; i++) {
            cache[i] = buildPlaceholders(i);
        }
        return cache;
    }

    private static String buildPlaceholders(int n) {
        StringBuilder out = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) out.append(',');
            out.append('?');
        }
        return out.toString();
    }
}
