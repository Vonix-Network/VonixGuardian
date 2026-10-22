/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import network.vonix.guardian.hytale.spi.GuardianServices;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Root {@code /guardian} command. Alias {@code vg} is registered here, before
 * {@link CommandRegistration} hands the tree to Hytale.
 */
public final class GuardianCommand extends AbstractCommand {

    private final CommandRuntime runtime;

    public GuardianCommand(GuardianServices services, Logger logger) {
        // The one-argument constructor leaves the command name null.
        super("guardian", "VonixGuardian audit, lookup, and rollback.");
        if (services == null || logger == null) {
            throw new IllegalArgumentException("services and logger are required");
        }
        this.runtime = new CommandRuntime(services, logger);
        addAliases("vg");
        addSubCommand(new StatusCommand(runtime, logger));
        addSubCommand(new InspectCommand(runtime, logger));
        addSubCommand(new LookupCommand(runtime, logger));
        addSubCommand(new FilterMutationCommand(runtime, logger, false));
        addSubCommand(new FilterMutationCommand(runtime, logger, true));
        addSubCommand(new UndoCommand(runtime, logger));
        addSubCommand(new PurgeCommand(runtime, logger));
        addSubCommand(new ReloadCommand(runtime, logger));
        addSubCommand(new HelpCommand(runtime, logger));
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        String denial = runtime.gate(context, CommandRuntime.USE_NODE, false);
        if (denial != null) {
            runtime.reply(context, runtime.error("[VonixGuardian] " + denial));
            return CompletableFuture.completedFuture(null);
        }
        for (String line : HelpCommand.lines()) {
            runtime.reply(context, line.startsWith("  ") ? runtime.muted(line) : runtime.primary(line));
        }
        return CompletableFuture.completedFuture(null);
    }
}
