/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.command.CommandSpec;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@code /guardian reload}. Fail closed, and not a world-changing confirm command. */
final class ReloadCommand extends GuardianSubcommand {

    ReloadCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.RELOAD, "Reload the Guardian configuration file from disk.",
                false, runtime, logger, CommandSpec.permissionNode(CommandSpec.RELOAD), true);
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
        Path path = guardian.configPath();
        runtime.reply(context, runtime.primary("[VonixGuardian] Reloading configuration..."));
        long generation = runtime.beginChat(actor);
        runtime.submit(sender, () -> reload(guardian, sender, actor, generation, path));
        return done();
    }

    private void reload(Guardian guardian, CommandSender sender, UUID actor, long generation, Path path) {
        Guardian.ReloadResult result;
        try {
            result = guardian.reloadConfig(path);
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Config reload failed", ex);
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] Reload failed: " + reason(ex) + " Live config state is unknown."));
            return;
        }
        String summary = "[VonixGuardian] Reload hot-swapped=" + result.hotSwapped().size()
                + " requiresRestart=" + result.requiresRestart().size()
                + " errors=" + result.errors().size();
        if (!result.errors().isEmpty()) {
            runtime.deliver(sender, actor, generation, runtime.error(
                    summary + ". Live config is unchanged. " + String.join("; ", result.errors())));
            return;
        }
        if (!result.requiresRestart().isEmpty()) {
            runtime.deliver(sender, actor, generation, runtime.warning(
                    summary + ". Restart required for: " + String.join(", ", result.requiresRestart())));
            return;
        }
        runtime.deliver(sender, actor, generation, runtime.success(summary));
    }
}
