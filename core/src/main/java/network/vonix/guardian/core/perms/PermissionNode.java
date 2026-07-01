package network.vonix.guardian.core.perms;

import network.vonix.guardian.core.action.ActionType;

/**
 * Canonical permission node registry for VonixGuardian commands.
 *
 * <p>Every subcommand and every reserved flag maps to exactly one node. Cells
 * pass the {@link #node()} string to {@link PermissionResolver#has(java.util.UUID, String)}
 * (or the LuckPerms bridge) &mdash; the {@link OpLevelFallback} default is honored
 * per node via {@link #defaultOpLevel()}.</p>
 *
 * <p>Node naming convention: {@code vonixguardian.<action>[.<qualifier>]},
 * chosen for compatibility with LuckPerms wildcard patterns
 * (e.g. {@code vonixguardian.*}).</p>
 *
 * <p>W3-B7 adds the 12 CoreProtect-compatible child nodes ({@code .lookup.block},
 * {@code .rollback.container}, {@code .restore.item}, &hellip;) plus {@link #childFor}
 * for family+category dispatch used by the result filter.</p>
 *
 * @since 1.1.7 (W3-B7)
 */
public enum PermissionNode {

    /** Base access to any {@code /vg} subcommand. */
    BASE                 ("vonixguardian.base",              2),

    // Query family
    LOOKUP               ("vonixguardian.lookup",            2),
    INSPECT              ("vonixguardian.inspect",           2),
    NEAR                 ("vonixguardian.near",              2),

    // Mutation family
    ROLLBACK             ("vonixguardian.rollback",          3),
    RESTORE              ("vonixguardian.restore",           3),
    UNDO                 ("vonixguardian.undo",              3),
    PURGE                ("vonixguardian.purge",             4),

    // Admin family
    RELOAD               ("vonixguardian.reload",            4),
    STATUS               ("vonixguardian.status",            3),
    MIGRATE_DB           ("vonixguardian.migrate_db",        4),
    CONSUMER             ("vonixguardian.consumer",          4),
    HELP                 ("vonixguardian.help",              0),

    // Advanced query flags (reserved; matches CoreProtect's granular perms)
    LOOKUP_CHAT          ("vonixguardian.lookup.chat",       3),
    LOOKUP_COMMAND       ("vonixguardian.lookup.command",    3),
    LOOKUP_IP            ("vonixguardian.lookup.ip",         4),
    BYPASS_INSPECTOR     ("vonixguardian.bypass.inspector",  0),

    // W3-B7: CoreProtect-compatible child nodes (per-category scoping)
    LOOKUP_BLOCK         ("vonixguardian.lookup.block",      2),
    LOOKUP_CONTAINER     ("vonixguardian.lookup.container",  2),
    LOOKUP_ITEM          ("vonixguardian.lookup.item",       2),
    LOOKUP_KILL          ("vonixguardian.lookup.kill",       2),
    LOOKUP_SESSION       ("vonixguardian.lookup.session",    2),
    LOOKUP_SIGN          ("vonixguardian.lookup.sign",       2),
    ROLLBACK_BLOCK       ("vonixguardian.rollback.block",    3),
    ROLLBACK_CONTAINER   ("vonixguardian.rollback.container",3),
    ROLLBACK_ITEM        ("vonixguardian.rollback.item",     3),
    RESTORE_BLOCK        ("vonixguardian.restore.block",     3),
    RESTORE_CONTAINER    ("vonixguardian.restore.container", 3),
    RESTORE_ITEM         ("vonixguardian.restore.item",      3);

    private final String node;
    private final int defaultOpLevel;

    PermissionNode(String node, int defaultOpLevel) {
        this.node = node;
        this.defaultOpLevel = defaultOpLevel;
    }

    /** @return the fully-qualified LuckPerms-compatible node string */
    public String node() {
        return node;
    }

    /**
     * @return the vanilla op-level (0..4) required when LuckPerms is absent
     *         and no explicit config override is set
     */
    public int defaultOpLevel() {
        return defaultOpLevel;
    }

    /**
     * Resolve the CoreProtect-style child node for the given (family, category) pair.
     *
     * <p>Fall-open: if no dedicated child exists (e.g. {@code (ROLLBACK, MESSAGE)}) the
     * {@code family} node is returned so an admin with {@code vonixguardian.rollback}
     * still passes the check &mdash; there is no way to "scope out" of a family that
     * has no per-category subdivision.</p>
     *
     * @param family one of {@link #LOOKUP}, {@link #ROLLBACK}, {@link #RESTORE}
     * @param cat    action category (must not be {@code null})
     * @return the specific child node when one is defined; otherwise {@code family}
     * @since 1.1.7 (W3-B7)
     */
    public static PermissionNode childFor(PermissionNode family, ActionType.Category cat) {
        if (family == null || cat == null) {
            throw new IllegalArgumentException("family and cat must be non-null");
        }
        switch (family) {
            case LOOKUP:
                switch (cat) {
                    case BLOCK:     return LOOKUP_BLOCK;
                    case CONTAINER: return LOOKUP_CONTAINER;
                    case ITEM:      return LOOKUP_ITEM;
                    case ENTITY:    return LOOKUP_KILL;
                    case SESSION:   return LOOKUP_SESSION;
                    case MESSAGE:   // ambiguous (chat/command/sign) — fall-open
                    default:        return LOOKUP;
                }
            case ROLLBACK:
                switch (cat) {
                    case BLOCK:     return ROLLBACK_BLOCK;
                    case CONTAINER: return ROLLBACK_CONTAINER;
                    case ITEM:      return ROLLBACK_ITEM;
                    default:        return ROLLBACK;
                }
            case RESTORE:
                switch (cat) {
                    case BLOCK:     return RESTORE_BLOCK;
                    case CONTAINER: return RESTORE_CONTAINER;
                    case ITEM:      return RESTORE_ITEM;
                    default:        return RESTORE;
                }
            default:
                return family;
        }
    }

    /**
     * Per-action variant of {@link #childFor(PermissionNode, ActionType.Category)} that
     * resolves the specific {@link ActionType} to the tightest child node.
     *
     * <p>This is used by {@link LookupPermissionFilter} to give sensible defaults for
     * the MESSAGE category, which {@code childFor(family, cat)} leaves fall-open
     * because it contains {@code CHAT}/{@code COMMAND}/{@code SIGN}.</p>
     *
     * @since 1.1.7 (W3-B7)
     */
    public static PermissionNode childForAction(PermissionNode family, ActionType type) {
        if (family == null || type == null) {
            throw new IllegalArgumentException("family and type must be non-null");
        }
        if (family == LOOKUP) {
            switch (type) {
                case CHAT:    return LOOKUP_CHAT;
                case COMMAND: return LOOKUP_COMMAND;
                case SIGN:    return LOOKUP_SIGN;
                default:      /* fall through to category */ break;
            }
        }
        return childFor(family, type.category());
    }
}
