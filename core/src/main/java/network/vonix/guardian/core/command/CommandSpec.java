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
 * <p>Permission nodes follow {@code vonixguardian.command.<sub>} —
 * e.g. {@code vonixguardian.command.rollback}.</p>
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
    /** {@code /vg status} — show queue depth, DB health, etc. */
    public static final String STATUS   = "status";
    /** {@code /vg reload} — reload {@code GuardianConfig}. */
    public static final String RELOAD   = "reload";
    /** {@code /vg help} — list subcommands. */
    public static final String HELP     = "help";

    /** Permission-node prefix — concatenated with the subcommand name. */
    public static final String PERMISSION_PREFIX = "vonixguardian.command.";

    private static final List<SubcommandSpec> ALL = List.of(
        sub(INSPECT,  "i",  false, "Toggle inspection mode — left/right click to query a block.",
            List.of()),
        sub(LOOKUP,   "l",  true,  "Query the audit log with filter tokens (u: t: r: a: i: e: #…).",
            List.of(ArgumentSpec.FILTER_TOKENS, ArgumentSpec.PAGE_NUMBER)),
        sub(ROLLBACK, "rb", true,  "Undo logged actions matching the given filter.",
            List.of(ArgumentSpec.FILTER_TOKENS)),
        sub(RESTORE,  "rs", true,  "Re-apply actions previously undone by /vg rollback.",
            List.of(ArgumentSpec.FILTER_TOKENS)),
        sub(PURGE,    null, true,  "Permanently delete audit rows older than the given filter window.",
            List.of(ArgumentSpec.FILTER_TOKENS)),
        sub(NEAR,     "n",  false, "Show recent activity in a small radius around you.",
            List.of()),
        sub(UNDO,     null, false, "Undo the last rollback or restore you performed.",
            List.of()),
        sub(STATUS,   null, false, "Show queue depth, DB health, and runtime stats.",
            List.of()),
        sub(RELOAD,   null, false, "Reload the Guardian configuration file from disk.",
            List.of()),
        sub(HELP,     "?",  false, "List subcommands and their syntax.",
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
     * @param subcommand subcommand literal (e.g. {@link #ROLLBACK})
     * @return {@code vonixguardian.command.<subcommand>}
     */
    public static String permissionNode(String subcommand) {
        return PERMISSION_PREFIX + subcommand;
    }

    private static SubcommandSpec sub(String name, String alias, boolean requiresArgs,
                                      String help, List<ArgumentSpec> args) {
        return new SubcommandSpec(name, alias, PERMISSION_PREFIX + name, requiresArgs, help, args);
    }
}
