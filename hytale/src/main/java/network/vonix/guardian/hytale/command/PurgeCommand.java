/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /guardian purge}. Deletes audit rows only. The in-game age floor
 * applies because a console sender is refused before this command runs.
 */
final class PurgeCommand extends GuardianSubcommand {

    private final RequiredArg<String> filterArg;

    PurgeCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.PURGE, "Permanently delete audit rows older than the given filter window.",
                true, runtime, logger, CommandSpec.permissionNode(CommandSpec.PURGE), true);
        filterArg = withRequiredArg("filter", "Filter tokens. t: must be older than the in-game age floor.",
                ArgTypes.GREEDY_STRING);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (!admit(context)) {
            return done();
        }
        String raw = context.get(filterArg);
        if (raw == null) {
            runtime.reply(context, runtime.error("[VonixGuardian] A filter is required. Nothing was deleted."));
            return done();
        }
        Guardian guardian = runtime.guardian();
        if (guardian == null) {
            notRunning(context);
            return done();
        }
        long minAge;
        try {
            minAge = guardian.config().purge().minAgeSecondsInGame();
        } catch (RuntimeException ex) {
            logger.warn(Guardian.MARKER, "Purge age floor unavailable", ex);
            runtime.reply(context, runtime.error(
                    "[VonixGuardian] Purge age floor is unavailable. Nothing was deleted."));
            return done();
        }
        CommandRuntime.Actor actor = runtime.capture(context);
        if (!requireIdentity(context, actor)) {
            return done();
        }
        CommandRuntime.Prepared prepared = runtime.prepare(
                guardian, actor, runtime.withoutConfirmToken(raw), CommandRuntime.QueryKind.PURGE);
        if (prepared.failed()) {
            runtime.reply(context, runtime.error("[VonixGuardian] " + prepared.error() + " Nothing was deleted."));
            return done();
        }
        CommandSender sender = context.sender();
        long generation = runtime.beginChat(actor.uuid());
        long floor = minAge;
        runtime.submit(sender, () -> purge(guardian, sender, actor.uuid(), generation, prepared.filter(), floor));
        return done();
    }

    private void purge(Guardian guardian, CommandSender sender, UUID actor, long generation,
                       QueryFilter filter, long minAge) {
        try {
            PurgeEngine.PurgeResult result = guardian.purgeEngine().purge(filter, minAge);
            StringBuilder text = new StringBuilder(160);
            text.append("[VonixGuardian] Purge removed ").append(result.deletedCount())
                    .append(" rows (minAge=").append(result.minAgeSeconds()).append("s).");
            if (filter.optimize()) {
                if (result.optimized()) {
                    text.append(" Optimize returned a result. Its completion flag is not visible to this package, so it is not reported as finished.");
                } else {
                    text.append(" Optimize did not return a result.");
                }
            }
            runtime.deliver(sender, actor, generation, runtime.warning(text.toString()));
        } catch (IllegalArgumentException ex) {
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] Purge refused: " + reason(ex) + " Nothing was deleted."));
        } catch (Exception ex) {
            logger.warn(Guardian.MARKER, "Purge failed", ex);
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] Purge failed: " + reason(ex) + " Deleted count is unknown."));
        }
    }
}
