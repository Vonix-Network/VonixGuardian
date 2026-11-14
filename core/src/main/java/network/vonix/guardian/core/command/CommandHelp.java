package network.vonix.guardian.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Text for {@code /vg help} and the bare-command usage hints.
 *
 * <p>Rendered from {@link CommandSpec} so a subcommand cannot be omitted, and
 * so the undo line cannot describe a history edit when the command changes
 * the world.</p>
 *
 * <p><b>Claims this text is not allowed to make.</b></p>
 * <p>{@code #preview} does not paint anything in the world &mdash; no ghost
 * blocks, no markers. It plans, counts, and keeps the exact action ids for
 * {@code /vg apply}. Describing it as a visual preview sent operators looking
 * for blocks that were never drawn.</p>
 * <p>{@code /vg undo} keeps twenty results per operator in memory, so a restart
 * clears it even though the batch rows survive in the database. The undo text
 * says so and names the path that does survive a restart.</p>
 * <p>Permission nodes are listed as
 * {@link CommandSpec#authoritativeNode(String)} resolves them, per subcommand.
 * The tree gates on two historical shapes plus one underscore; a page that
 * showed a single uniform {@code vonixguardian.command.<sub>} had operators
 * granting nodes nothing evaluates.</p>
 */
public final class CommandHelp {

    private CommandHelp() {}

    public static List<String> full() {
        List<String> lines = new ArrayList<>();
        lines.add("[VonixGuardian] Commands (also /co and /guardian):");
        for (SubcommandSpec spec : CommandSpec.all()) {
            String alias = spec.alias() == null ? "" : "  (/vg " + spec.alias() + ")";
            lines.add("/vg " + spec.name() + alias + " — " + spec.helpSummary());
        }
        lines.add("Lookup pages: /vg lookup <page>[:<perPage>] before filter");
        lines.add("Filter tokens: u:<player>  t:<time or time-time>  r:<blocks|#global|#world_name|#worldedit>");
        lines.add("               a:<action>  i:<id>  e:<id>");
        lines.add("Flags: #preview and #count do not change the world or delete rows. Also #verbose #silent #optimize.");
        lines.add("#preview draws nothing: it counts the plan and keeps it for /vg apply or /vg cancel.");
        lines.add("a:click rows are interactions (right-click, cake, anvil, item frame, taming)."
            + " They are recorded and never rolled back.");
        lines.addAll(permissionLines());
        return List.copyOf(lines);
    }

    /**
     * The permission nodes the tree actually evaluates, derived from
     * {@link CommandSpec#gatedNodes()} so the text cannot drift from the
     * brigadier gates, plus the nodes nothing checks.
     *
     * <p>Three shapes exist for historical reasons and all three appear here:
     * {@code vonixguardian.<sub>} for most subcommands,
     * {@code vonixguardian.command.<sub>} for {@code config} / {@code entitylog}
     * / {@code teleport} / {@code give}, and {@code vonixguardian.migrate_db}
     * with an underscore. Each subcommand's own usage line also names its node,
     * which is the string to type in LuckPerms.</p>
     */
    public static List<String> permissionLines() {
        List<String> nodes = CommandSpec.gatedNodes();
        String base = "vonixguardian.";
        List<String> plain = new ArrayList<>();
        List<String> dotted = new ArrayList<>();
        for (String node : nodes) {
            if (node.startsWith(base + "command.")) {
                dotted.add(node.substring(base.length()));   // "command.config"
            } else if (node.startsWith(base)) {
                plain.add(node.substring(base.length()));
            } else {
                dotted.add(node);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Permission nodes (" + nodes.size() + ") — grant these; permissions.perNodeOpLevels"
            + " uses the same strings:");
        // Brace form: one line per subtree, so a wrapping chat line still groups
        // the nodes that share a prefix.
        lines.add("  vonixguardian.{" + String.join(",", plain) + "}");
        List<String> exceptions = new ArrayList<>();
        for (String node : dotted) {
            exceptions.add(node.startsWith("command.") ? base + node : node);
        }
        lines.add("  " + exceptions.size() + " more are not vonixguardian.<sub>: "
            + String.join(", ", exceptions) + ".");
        lines.add("  Reserved, enforced by nothing today: " + String.join(", ", unenforcedNodes()) + ".");
        return List.copyOf(lines);
    }

    /**
     * Permission nodes that exist in
     * {@link network.vonix.guardian.core.perms.PermissionNode} but that no code
     * path reads. Listing them stops an operator from granting
     * {@code lookup.ip} and expecting IPs to appear in lookup output.
     *
     * <p>{@code entitylog} is deliberately not here: it reuses
     * {@code vonixguardian.command.config} and therefore does enforce something.</p>
     */
    public static List<String> unenforcedNodes() {
        return List.of(
            "vonixguardian.lookup.ip",
            "vonixguardian.bypass.inspector",
            "vonixguardian.help");
    }

    public static List<String> usage(String name) {
        if (name == null || name.isBlank() || "root".equals(name) || "help".equals(name)) {
            return full();
        }
        return switch (name) {
            case "lookup" -> List.of(
                "[VonixGuardian] /vg lookup <page>[:<perPage>] before filter",
                "  Example: /vg lookup r:10 t:2h",
                "  Example: /vg lookup #count r:20",
                "  Tokens: u: user, t: time, r: radius, a: action, i: include, e: exclude, #preview #count",
                "  Needs " + CommandSpec.authoritativeNode("lookup") + ".");
            case "rollback" -> List.of(
                "[VonixGuardian] /vg rollback <filter>  (alias /vg rb)",
                "  Example: /vg rollback r:10 t:2m",
                "  Example: /vg rollback r:20 t:5m #preview",
                "  This changes blocks in the world. Default radius is 10.",
                "  #preview changes nothing and draws nothing in the world: it counts the plan",
                "  and holds it for /vg apply (changes those blocks) or /vg cancel (drops it).",
                "  Needs " + CommandSpec.authoritativeNode("rollback") + ".");
            case "restore" -> List.of(
                "[VonixGuardian] /vg restore <filter>  (alias /vg rs)",
                "  Example: /vg restore r:10 t:2m",
                "  This puts rolled-back blocks back into the world. Default radius is 10.",
                "  #preview behaves as it does for rollback: a count, no blocks drawn.",
                "  Needs " + CommandSpec.authoritativeNode("restore") + ".");
            case "apply" -> List.of(
                "[VonixGuardian] /vg apply",
                "  Runs the exact plan the last #preview kept, then clears it.",
                "  This changes blocks in the world. /vg cancel drops the plan instead.",
                "  Needs the node of the pending preview: "
                    + CommandSpec.authoritativeNode("rollback") + " or "
                    + CommandSpec.authoritativeNode("restore") + ".");
            case "purge" -> List.of(
                "[VonixGuardian] /vg purge <filter>",
                "  Example: /vg purge t:30d",
                "  Example: /vg purge t:40d-60d r:#nether #preview",
                "  A bare purge covers every world. Name one with r:#nether, r:#overworld, r:#end, or r:#world_<name>.",
                "  A single t: deletes rows older than that age. t:40d-60d deletes only the rows inside that window.",
                "  #preview and #count do not delete.",
                "  Console refuses anything newer than 24h. In-game refuses anything newer than 30d.",
                "  Needs " + CommandSpec.authoritativeNode("purge") + ".");
            case "undo" -> List.of(
                "[VonixGuardian] /vg undo",
                "  Reverses your last rollback or restore. This changes the world again.",
                "  Remembers the last " + network.vonix.guardian.core.rollback.UndoStack.MAX_PER_ACTOR
                    + " operations per operator, in memory only: a server restart clears them.",
                "  After a restart, /vg batch lists an unfinished rollback and its action ids;",
                "  /vg restore with a matching filter puts those blocks back.",
                "  Needs " + CommandSpec.authoritativeNode("undo") + ".");
            case "batch" -> List.of(
                "[VonixGuardian] /vg batch [list|show <id>|close <id>]",
                "  Example: /vg batch",
                "  Example: /vg batch show 17",
                "  Example: /vg batch close 17",
                "  Lists a rollback that did not finish. Closing marks the audit row finished.",
                "  The world is not changed. Unlike /vg undo, this survives a restart.",
                "  Needs " + CommandSpec.authoritativeNode("batch") + ".");
            default -> full();
        };
    }
}
