/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Shared admission for a {@code /guardian} subcommand: Vonix node, plugin base
 * permission, and the explicit {@code --confirm} flag on destructive commands.
 */
abstract class GuardianSubcommand extends AbstractCommand {

    final CommandRuntime runtime;
    final Logger logger;
    private final String permissionNode;
    private final boolean destructive;
    private final boolean mutating;

    /**
     * @param destructive registers Hytale's {@code confirm} flag. The one-argument
     *                    {@link AbstractCommand} constructor stores a null name,
     *                    so this always uses the three-argument form.
     */
    GuardianSubcommand(String name, String description, boolean destructive,
                       CommandRuntime runtime, Logger logger,
                       String permissionNode, boolean mutating) {
        super(name, description, destructive);
        this.runtime = runtime;
        this.logger = logger;
        this.permissionNode = permissionNode;
        this.destructive = destructive;
        this.mutating = mutating;
    }

    final boolean admit(CommandContext context) {
        String denial = runtime.gate(context, permissionNode, mutating);
        if (denial != null) {
            runtime.reply(context, runtime.error("[VonixGuardian] " + denial));
            return false;
        }
        if (destructive && !runtime.confirmed(this, context)) {
            runtime.reply(context, runtime.error(
                    "[VonixGuardian] Refusing to run without an explicit --confirm. Nothing was changed."));
            return false;
        }
        return true;
    }

    final boolean requireIdentity(CommandContext context, CommandRuntime.Actor actor) {
        if (actor.player() && actor.uuid() == null) {
            runtime.reply(context, runtime.error(
                    "[VonixGuardian] Your identity is unavailable. Nothing was changed."));
            return false;
        }
        return true;
    }

    final void notRunning(CommandContext context) {
        runtime.reply(context, runtime.error("[VonixGuardian] VonixGuardian is not running. Nothing was changed."));
    }

    static String reason(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }
}
