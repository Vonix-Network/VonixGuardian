package network.vonix.guardian.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Text for {@code /vg help} and the bare-command usage hints.
 *
 * <p>Rendered from {@link CommandSpec} so a subcommand cannot be omitted, and
 * so the undo line cannot describe a history edit when the command changes
 * the world.</p>
 */
public final class CommandHelp {

    private CommandHelp() {}

    public static List<String> full() {
        List<String> lines = new ArrayList<>();
        lines.add("[VonixGuardian] Commands (also /co and /guardian):");
        for (SubcommandSpec spec : CommandSpec.all()) {
            String alias = spec.alias() == null ? "" : "  (/" + spec.alias() + ")";
            lines.add("/vg " + spec.name() + alias + " — " + spec.helpSummary());
        }
        lines.add("Lookup pages: /vg lookup <page>[:<perPage>] before filter");
        lines.add("Filter tokens: u:<player>  t:<time or time-time>  r:<blocks|#global|#world_name|#worldedit>");
        lines.add("               a:<action>  i:<id>  e:<id>");
        lines.add("Flags: #preview and #count do not change the world or delete rows. Also #verbose #silent #optimize.");
        return List.copyOf(lines);
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
                "  Tokens: u: user, t: time, r: radius, a: action, i: include, e: exclude, #preview #count");
            case "rollback" -> List.of(
                "[VonixGuardian] /vg rollback <filter>  (alias /vg rb)",
                "  Example: /vg rollback r:10 t:2m",
                "  Example: /vg rollback #preview r:20 t:5m",
                "  This changes blocks in the world. #preview shows the plan first. Default radius is 10.");
            case "restore" -> List.of(
                "[VonixGuardian] /vg restore <filter>  (alias /vg rs)",
                "  Example: /vg restore r:10 t:2m",
                "  This puts rolled-back blocks back into the world. Default radius is 10.");
            case "purge" -> List.of(
                "[VonixGuardian] /vg purge <filter>",
                "  Example: /vg purge t:30d",
                "  Example: /vg purge t:40d-60d r:#nether #preview",
                "  A bare purge covers every world. Name one with r:#nether, r:#overworld, r:#end, or r:#world_<name>.",
                "  A single t: deletes rows older than that age. t:40d-60d deletes only the rows inside that window.",
                "  #preview and #count do not delete.",
                "  Console refuses anything newer than 24h. In-game refuses anything newer than 30d.");
            case "undo" -> List.of(
                "[VonixGuardian] /vg undo",
                "  Reverses your last rollback or restore. This changes the world again.");
            case "batch" -> List.of(
                "[VonixGuardian] /vg batch [list|show <id>|close <id>]",
                "  Example: /vg batch",
                "  Example: /vg batch show 17",
                "  Example: /vg batch close 17",
                "  Lists a rollback that did not finish. Closing marks the audit row finished.",
                "  The world is not changed.");
            default -> full();
        };
    }
}
