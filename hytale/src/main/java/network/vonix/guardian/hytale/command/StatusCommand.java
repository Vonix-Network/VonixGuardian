/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.diagnostics.GuardianStatus;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@code /guardian status}. Database work runs off the command thread. */
final class StatusCommand extends GuardianSubcommand {

    StatusCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.STATUS, "Show queue depth, database health, and runtime stats.",
                false, runtime, logger, CommandSpec.permissionNode(CommandSpec.STATUS), false);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (!admit(context)) {
            return done();
        }
        Guardian guardian = runtime.guardian();
        if (guardian == null) {
            notRunning(context);
            return done();
        }
        CommandSender sender = context.sender();
        UUID actor = runtime.capture(context).uuid();
        long generation = runtime.beginChat(actor);
        runtime.submit(sender, () -> render(guardian, sender, actor, generation));
        return done();
    }

    private void render(Guardian guardian, CommandSender sender, UUID actor, long generation) {
        List<String> lines;
        try {
            lines = GuardianStatus.render(guardian);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Status render failed", ex);
            runtime.deliver(sender, actor, generation,
                    runtime.error("[VonixGuardian] Status failed: " + reason(ex)));
            return;
        }
        if (lines.isEmpty()) {
            runtime.deliver(sender, actor, generation,
                    runtime.warning("[VonixGuardian] Status returned no lines."));
            return;
        }
        for (String line : lines) {
            Message message = line.startsWith("§ ")
                    ? runtime.section(line.substring(2))
                    : runtime.muted(line);
            runtime.deliver(sender, actor, generation, message);
        }
    }
}
