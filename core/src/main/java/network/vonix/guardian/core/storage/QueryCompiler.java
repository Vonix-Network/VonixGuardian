package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;

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

    /**
     * Columns we will ever splice into SQL by name (e.g. for ORDER BY, projection).
     * Anything not in this set MUST be bound, not concatenated.
     */
    public static final Set<String> COLUMN_WHITELIST = Set.of(
        "id", "ts", "type", "user_id", "world_id", "x", "y", "z",
        "target", "meta", "amount", "rolled_back", "source_tag",
        "uuid", "name", "world_key"
    );

    /** The canonical SELECT projection — kept in one place so both query() and count() agree. */
    public static final String SELECT_PROJECTION =
        "a.id, a.ts, a.type, u.uuid, u.name, w.world_key, "
      + "a.x, a.y, a.z, a.target, a.meta, a.amount, a.rolled_back, a.source_tag";

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
            clauses.add("a.target IN (" + placeholders(f.include().size()) + ")");
            binds.addAll(f.include());
        }
        if (f.exclude() != null && !f.exclude().isEmpty()) {
            clauses.add("a.target NOT IN (" + placeholders(f.exclude().size()) + ")");
            binds.addAll(f.exclude());
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

    private static String placeholders(int n) {
        StringBuilder out = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) out.append(',');
            out.append('?');
        }
        return out.toString();
    }
}
