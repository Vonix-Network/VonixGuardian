package network.vonix.guardian.core.blacklist;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Precompiled lookup that decides whether an {@link Action} should be
 * suppressed based on {@link BlacklistFile.Parsed} rules.
 *
 * <p>All checks are O(1) hash lookups. Case-insensitive matching for
 * user names, command names, and target ids (matches CoreProtect).
 *
 * @since 1.1.7 (W3-B6)
 */
public final class BlacklistMatcher {

    private final Set<String> userNames;      // lower-cased
    private final Set<UUID>   userUuids;
    private final Set<String> commandPrefixes; // lower-cased, without leading '/'
    private final Set<String> blockIds;        // lower-cased
    private final Set<String> entityIds;       // lower-cased
    private final Set<BlacklistFile.Composite> composites; // id lower, user lower

    /**
     * @param p parsed blacklist rules; must not be {@code null}
     */
    public BlacklistMatcher(BlacklistFile.Parsed p) {
        Objects.requireNonNull(p, "parsed");
        this.userNames = lower(p.userNames());
        this.userUuids = Set.copyOf(p.userUuids());
        this.commandPrefixes = lower(p.commandPrefixes());
        this.blockIds = lower(p.blockIds());
        this.entityIds = lower(p.entityIds());
        Set<BlacklistFile.Composite> comps = new HashSet<>();
        for (BlacklistFile.Composite c : p.composites()) {
            comps.add(new BlacklistFile.Composite(
                    c.id().toLowerCase(Locale.ROOT),
                    c.userName().toLowerCase(Locale.ROOT)));
        }
        this.composites = Set.copyOf(comps);
    }

    private static Set<String> lower(Set<String> src) {
        Set<String> out = new HashSet<>(src.size() * 2);
        for (String s : src) out.add(s.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    /** @return total rule count across all kinds */
    public int size() {
        return userNames.size() + userUuids.size() + commandPrefixes.size()
             + blockIds.size() + entityIds.size() + composites.size();
    }

    /** @return {@code true} if no rules are loaded */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * @param a candidate action; must not be {@code null}
     * @return {@code true} if the action matches any blacklist rule and should
     *         be dropped
     */
    public boolean matches(Action a) {
        Objects.requireNonNull(a, "action");

        String actorLower = a.actorName() == null ? null
                : a.actorName().toLowerCase(Locale.ROOT);

        if (actorLower != null && userNames.contains(actorLower)) return true;
        if (a.actorUuid() != null && userUuids.contains(a.actorUuid())) return true;

        ActionType t = a.type();

        // command:<name>  — matches COMMAND actions whose message starts with "/<name>"
        if (t == ActionType.COMMAND && !commandPrefixes.isEmpty() && a.targetId() != null) {
            String msg = a.targetId().trim().toLowerCase(Locale.ROOT);
            if (msg.startsWith("/")) msg = msg.substring(1);
            // first token = command name (strip args)
            int sp = msg.indexOf(' ');
            String head = sp < 0 ? msg : msg.substring(0, sp);
            if (commandPrefixes.contains(head)) return true;
        }

        String tgtLower = a.targetId() == null ? null
                : a.targetId().toLowerCase(Locale.ROOT);

        // block:<id> — BLOCK_PLACE / BLOCK_BREAK only
        if (tgtLower != null && (t == ActionType.BLOCK_PLACE || t == ActionType.BLOCK_BREAK)
                && blockIds.contains(tgtLower)) {
            return true;
        }

        // entity:<id> — ENTITY_KILL / ENTITY_SPAWN
        if (tgtLower != null && (t == ActionType.ENTITY_KILL || t == ActionType.ENTITY_SPAWN)
                && entityIds.contains(tgtLower)) {
            return true;
        }

        // composite <id>@<user> — id matches action's target, user matches actor
        if (!composites.isEmpty() && tgtLower != null && actorLower != null) {
            if (composites.contains(new BlacklistFile.Composite(tgtLower, actorLower))) {
                return true;
            }
        }

        return false;
    }
}
