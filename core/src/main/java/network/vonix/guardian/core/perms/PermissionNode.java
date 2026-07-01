package network.vonix.guardian.core.perms;

/**
 * Canonical permission node registry for VonixGuardian commands.
 *
 * <p>Every subcommand and every reserved flag maps to exactly one node. Cells
 * pass the {@link #node()} string to {@link PermissionResolver#has(Object, String)}
 * (or the LuckPerms bridge) &mdash; the {@link OpLevelFallback} default is honored
 * per node via {@link #defaultOpLevel()}.</p>
 *
 * <p>Node naming convention: {@code vonixguardian.<action>[.<qualifier>]},
 * chosen for compatibility with LuckPerms wildcard patterns
 * (e.g. {@code vonixguardian.*}).</p>
 *
 * <p>To add a new node: add an enum constant here and reference it from the
 * command dispatcher &mdash; do NOT hand-write raw permission strings in cells.</p>
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
    BYPASS_INSPECTOR     ("vonixguardian.bypass.inspector",  0);

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
}
