package network.vonix.guardian.core.command;

import java.util.List;

/**
 * Loader-agnostic spec for the {@code /vg} command tree.
 *
 * <p>Each MC version's {@code common} module translates these constants and the
 * {@link #all()} tree into brigadier nodes. The {@code core} module never
 * depends on brigadier directly so 1.18.2 and 1.21.1 can use different
 * brigadier surfaces from the same data.</p>
 *
 * <p><b>Permission nodes.</b> The string in {@link SubcommandSpec#permissionNode()}
 * is {@code vonixguardian.command.<sub>}, which is a <em>legacy alias</em>, not the
 * node a server actually grants. The authoritative nodes live in
 * {@link network.vonix.guardian.core.perms.PermissionNode} and follow two
 * shapes for historical reasons:</p>
 * <ul>
 *   <li>{@code vonixguardian.<name>} — {@code lookup}, {@code inspect},
 *       {@code near}, {@code rollback}, {@code restore}, {@code undo},
 *       {@code purge}, {@code status}, {@code reload}, {@code consumer},
 *       {@code base}, {@code help};</li>
 *   <li>{@code vonixguardian.command.<name>} — {@code config},
 *       {@code teleport}, {@code give} only.</li>
 * </ul>
 * <p>{@code PermissionNode.fromNodeOrCommandAlias} maps the legacy
 * {@code vonixguardian.command.*} spelling onto the canonical enum value, but
 * that map only feeds the op-level fallback and
 * {@code PermissionResolver.has(UUID, String)}. The enum overload the command
 * cells use checks LuckPerms for {@code node.node()} only, so a grant entered
 * with the legacy spelling is <em>not</em> seen by the command gate. Grant the
 * names {@link #authoritativeNode(String)} returns.</p>
 *
 * <p><b>Aliases.</b> {@link SubcommandSpec#alias()} is printed by
 * {@code /vg help}, so every alias listed here must also be registered as a
 * brigadier literal by every loader cell. An alias that is advertised and never
 * registered is a lie in the help text; {@code n} and {@code ?} were removed for
 * exactly that reason.</p>
 */
public final class CommandSpec {

    // ---- Top-level ---------------------------------------------------------

    /** Root literal: {@code /vg}. */
    public static final String ROOT = "vg";

    /** Top-level alias literal: {@code /guardian}. */
    public static final String ALIAS = "guardian";

    // ---- Subcommands (frozen by SHARED-CONTRACTS § 7) ----------------------

    /** {@code /vg inspect} — toggle inspection mode. */
    public static final String INSPECT  = "inspect";
    /** {@code /vg lookup <filter…>} — query the audit log. */
    public static final String LOOKUP   = "lookup";
    /** {@code /vg rollback <filter…>} — undo logged actions. */
    public static final String ROLLBACK = "rollback";
    /** {@code /vg restore <filter…>} — re-apply previously rolled-back actions. */
    public static final String RESTORE  = "restore";
    /** {@code /vg purge <filter>} — permanently delete old log rows matching an age-bounded filter. */
    public static final String PURGE    = "purge";
    /** {@code /vg near} — quick look at recent activity around the caller. */
    public static final String NEAR     = "near";
    /** {@code /vg undo} — undo the caller's last rollback/restore. */
    public static final String UNDO     = "undo";
    /** {@code /vg batch} — inspect or close an unfinished rollback without changing the world. */
    public static final String BATCH    = "batch";
    /** {@code /vg status} — show queue depth, DB health, etc. */
    public static final String STATUS   = "status";
    /** {@code /vg reload} — reload {@code GuardianConfig}. */
    public static final String RELOAD   = "reload";
    /** {@code /vg help} — list subcommands. */
    public static final String HELP     = "help";
    /** {@code /vg apply} — apply the pending rollback/restore preview. */
    public static final String APPLY    = "apply";
    /** {@code /vg cancel} — cancel the pending preview. */
    public static final String CANCEL   = "cancel";
    /** {@code /vg consumer} — pause/resume/toggle the write consumer. */
    public static final String CONSUMER = "consumer";
    /** {@code /vg config} — get/set hot-swap-safe config keys. */
    public static final String CONFIG   = "config";
    /** {@code /vg entitylog} — manage the modded-entity griefing allowlist. */
    public static final String ENTITYLOG = "entitylog";
    /** {@code /vg teleport} — CoreProtect-parity teleport. */
    public static final String TELEPORT = "teleport";
    /** {@code /vg give} — CoreProtect-parity give. */
    public static final String GIVE     = "give";
    /** {@code /vg migrate-db} — copy the catalog between JDBC backends. */
    public static final String MIGRATE_DB = "migrate-db";

    /** Permission-node prefix — concatenated with the subcommand name. */
    public static final String PERMISSION_PREFIX = "vonixguardian.command.";

    private static final List<SubcommandSpec> ALL = List.of(
        sub(INSPECT,  "i",  false, "Toggle inspection mode — left/right click to query a block.",
            List.of()),
        sub(LOOKUP,   "l",  true,  "Query the audit log with filter tokens (u: t: r: a: i: e: #…).",
            List.of(ArgumentSpec.FILTER_TOKENS, ArgumentSpec.PAGE_NUMBER)),
        sub(ROLLBACK, "rb", true,  "Undo logged actions in the world (default radius 10).",
            List.of(ArgumentSpec.FILTER_TOKENS)),
        sub(RESTORE,  "rs", true,  "Put back actions that a rollback changed in the world.",
            List.of(ArgumentSpec.FILTER_TOKENS)),
        sub(PURGE,    null, true,  "Permanently delete old log rows in every world unless you name one. #preview and #count do not delete.",
            List.of(ArgumentSpec.FILTER_TOKENS)),
        sub(NEAR,     null, false, "Show recent activity in a small radius around you.",
            List.of()),
        sub(UNDO,     null, false, "Reverse your last rollback or restore. This changes the world again.",
            List.of()),
        sub(BATCH,    null, false, "List, show, or close an unfinished rollback. Closing does not change the world.",
            List.of()),
        sub(STATUS,   null, false, "Show queue depth, storage reachability and schema, and runtime stats.",
            List.of()),
        sub(RELOAD,   null, false, "Reload the Guardian configuration file from disk.",
            List.of()),
        sub(HELP,     null, false, "List subcommands and their syntax.",
            List.of()),
        sub(APPLY,    null, false, "Apply the pending preview. This changes the world.",
            List.of()),
        sub(CANCEL,   null, false, "Drop the pending preview. The world is not changed.",
            List.of()),
        sub(CONSUMER, null, false, "Pause, resume, or toggle the audit write consumer.",
            List.of()),
        sub(CONFIG,   null, true,  "Get or set a hot-swap-safe configuration key.",
            List.of()),
        sub(ENTITYLOG, null, false, "List, add, or remove modded entities from the griefing allowlist.",
            List.of()),
        sub(TELEPORT, "tp", true,  "Teleport to a world coordinate from a lookup row.",
            List.of()),
        sub(GIVE,     null, true,  "Give an item id captured by lookup.",
            List.of()),
        sub(MIGRATE_DB, null, true, "Copy the Guardian catalog to another JDBC backend.",
            List.of())
    );

    private CommandSpec() {
        // constants only
    }

    /**
     * Returns the full subcommand tree as data.
     *
     * <p>The returned list is unmodifiable and ordered the same way {@code /vg help}
     * should display its entries.</p>
     *
     * @return immutable ordered list of every subcommand
     */
    public static List<SubcommandSpec> all() {
        return ALL;
    }

    /**
     * Returns the canonical permission node for the given subcommand name.
     *
     * <p>This is the <em>legacy</em> spelling {@code vonixguardian.command.<sub>}.
     * It is accepted by {@code PermissionResolver} through
     * {@code PermissionNode.COMMAND_ALIASES}, so it is safe to grant, but it is
     * not the node the enum gates on. Prefer {@link #authoritativeNode(String)}
     * for anything an operator reads.</p>
     *
     * @param subcommand subcommand literal (e.g. {@link #ROLLBACK})
     * @return {@code vonixguardian.command.<subcommand>}
     */
    public static String permissionNode(String subcommand) {
        return PERMISSION_PREFIX + subcommand;
    }

    /**
     * Returns the permission node string the server actually evaluates for a
     * subcommand — the {@link network.vonix.guardian.core.perms.PermissionNode}
     * enum value, which is what LuckPerms grants and what
     * {@code permissions.perNodeOpLevels} keys on.
     *
     * <p>The two spellings disagree for most subcommands: {@code lookup} is
     * {@code vonixguardian.lookup}, not {@code vonixguardian.command.lookup}.
     * Only {@code config}, {@code entitylog}, {@code teleport} and {@code give}
     * genuinely use the {@code .command.} form, and {@code migrate-db} is
     * {@code vonixguardian.migrate_db} (underscore). Help text has to print the
     * real one, or the operator grants a node nothing checks.</p>
     *
     * @param subcommand subcommand literal (e.g. {@link #LOOKUP})
     * @return the canonical node string, or the legacy alias when the subcommand
     *         has no enum node (e.g. {@code entitylog}, which reuses
     *         {@code vonixguardian.command.config})
     */
    public static String authoritativeNode(String subcommand) {
        // Both tables live in core and have no Minecraft dependency.
        String key = subcommand == null ? "" : subcommand;
        String explicit = GATE_OVERRIDES.get(key);
        String legacy = PERMISSION_PREFIX + key;
        network.vonix.guardian.core.perms.PermissionNode node =
                network.vonix.guardian.core.perms.PermissionNode.fromNodeOrCommandAlias(
                        explicit != null ? explicit : legacy);
        return node == null ? legacy : node.node();
    }

    /**
     * Subcommands whose gate is not derivable from their own name.
     *
     * <p>{@code /vg apply} and {@code /vg cancel} act on whatever preview is
     * pending, so {@code GuardianCommands.hasPreviewPermission} picks
     * {@code rollback} or {@code restore} per preview — there is no
     * {@code apply} node to grant. {@code /vg entitylog} edits the config file
     * through {@code PermissionNode.CONFIG}. {@code /vg help} and the root
     * literal ({@code ""}) are gated by {@code PermissionNode.BASE} only:
     * {@code vonixguardian.help} exists in the enum and no cell checks it.</p>
     */
    private static final java.util.Map<String, String> GATE_OVERRIDES = java.util.Map.of(
        "",          "vonixguardian.command.use",
        HELP,        "vonixguardian.command.use",
        APPLY,       "vonixguardian.command.rollback",
        CANCEL,      "vonixguardian.command.rollback",
        ENTITYLOG,   "vonixguardian.command.config"
    );

    /**
     * Every permission node the {@code /vg} tree actually gates on, in command
     * order and de-duplicated. {@code /vg help} prints this list; it is the
     * answer to "what do I type in LuckPerms".
     *
     * @return immutable ordered list of canonical node strings
     */
    public static List<String> gatedNodes() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(authoritativeNode(""));           // vonixguardian.base (root gate)
        for (SubcommandSpec spec : ALL) {
            out.add(authoritativeNode(spec.name()));
        }
        return List.copyOf(out);
    }

    private static SubcommandSpec sub(String name, String alias, boolean requiresArgs,
                                      String help, List<ArgumentSpec> args) {
        return new SubcommandSpec(name, alias, PERMISSION_PREFIX + name, requiresArgs, help, args);
    }
}
