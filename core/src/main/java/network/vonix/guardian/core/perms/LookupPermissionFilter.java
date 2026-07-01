package network.vonix.guardian.core.perms;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import network.vonix.guardian.core.action.Action;

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
            if (resolver.has(uuid, child.node())) {
                out.add(a);
            }
            // else: silently drop
        }
        return out;
    }
}
