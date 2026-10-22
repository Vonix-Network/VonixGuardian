/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import network.vonix.guardian.core.command.CommandSpec;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** {@code /guardian help}. The engine already treats {@code ?} as its help token. */
final class HelpCommand extends GuardianSubcommand {

    HelpCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.HELP, "List VonixGuardian subcommands.",
                false, runtime, logger, CommandSpec.permissionNode(CommandSpec.HELP), false);
    }

    static List<String> lines() {
        return List.of(
                "[VonixGuardian] /guardian (alias /vg)",
                "  status — queue, database, and runtime stats.",
                "  inspect (i) — toggle inspect mode.",
                "  lookup (l) <filter> — audit query. A leading page or page:perPage token sets the page (perPage 1..50).",
                "  rollback (rb) <filter> --confirm — undo logged actions. Default radius is 10 at your position.",
                "  restore (rs) <filter> --confirm — re-apply rolled-back actions. Default radius is 10.",
                "  undo --confirm — reverse your last rollback or restore.",
                "  purge <filter> --confirm — delete audit rows older than the in-game age floor.",
                "  reload — reload the configuration file. Does not change the world.",
                "  help — show this list.",
                "  #preview and #count do not change the world. Run the same filter again without #preview to apply."
        );
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (!admit(context)) {
            return done();
        }
        for (String line : lines()) {
            runtime.reply(context, line.startsWith("  ") ? runtime.muted(line) : runtime.primary(line));
        }
        return done();
    }
}
