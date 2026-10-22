/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.command.CommandSpec;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackCancelledException;
import network.vonix.guardian.core.rollback.RollbackEngine;
import network.vonix.guardian.core.rollback.RollbackLimitExceededException;
import network.vonix.guardian.core.rollback.RollbackOptions;
import network.vonix.guardian.core.rollback.RollbackPlan;
import network.vonix.guardian.core.rollback.RollbackResult;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /guardian undo}. World-mutating: fail closed, and require
 * {@code --confirm}. The stack entry is removed only after a successful
 * inverse or a result that changed the world.
 */
final class UndoCommand extends GuardianSubcommand {

    UndoCommand(CommandRuntime runtime, Logger logger) {
        super(CommandSpec.UNDO, "Undo the last rollback or restore you performed.",
                true, runtime, logger, CommandSpec.permissionNode(CommandSpec.UNDO), true);
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
        CommandRuntime.Actor actor = runtime.capture(context);
        if (!requireIdentity(context, actor)) {
            return done();
        }
        UUID key = actor.uuid();
        var peeked = guardian.undoStack().peek(key);
        if (peeked.isEmpty()) {
            runtime.reply(context, runtime.muted("[VonixGuardian] Nothing to undo."));
            return done();
        }
        RollbackResult previous = peeked.get();
        if (previous.originalFilter() == null || previous.affectedIds().isEmpty()) {
            guardian.undoStack().popIfSame(key, previous);
            logger.info(Guardian.MARKER,
                    "Undo dropped a legacy or empty history entry; affectedCount={}",
                    previous.affectedCount());
            runtime.reply(context, runtime.warning(
                    "[VonixGuardian] Undo: dropped " + previous.affectedCount()
                            + " entries from history (legacy or empty entry — world state not reverted)."));
            return done();
        }
        QueryFilter exact = idFilter(previous.originalFilter(), previous.affectedIds());
        RollbackResult.Mode inverse = previous.inverseMode();
        CommandSender sender = context.sender();
        long generation = runtime.beginChat(key);
        runtime.submit(sender, () -> run(guardian, sender, key, generation, previous, exact, inverse));
        return done();
    }

    private void run(Guardian guardian, CommandSender sender, UUID actor, long generation,
                     RollbackResult previous, QueryFilter filter, RollbackResult.Mode inverse) {
        RollbackEngine engine = guardian.rollbackEngine();
        RollbackOptions options = MutationReport.options(runtime, sender, actor, generation);
        RollbackPlan plan;
        try {
            plan = engine.plan(filter, inverse, actor, options);
        } catch (IllegalArgumentException | RollbackLimitExceededException | RollbackCancelledException ex) {
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] Undo refused: " + reason(ex) + " Nothing was changed."));
            return;
        } catch (Exception ex) {
            logger.warn(Guardian.MARKER, "Undo planning failed", ex);
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] Undo planning failed: " + reason(ex) + " Nothing was changed."));
            return;
        }
        RollbackResult result;
        try {
            result = engine.execute(plan, false);
        } catch (Exception ex) {
            logger.warn(Guardian.MARKER, "Undo execution failed", ex);
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] Undo failed without a result. The world may have changed. The undo entry was kept."));
            return;
        }
        if (result.isSuccess() || result.mutatedWorld()) {
            guardian.undoStack().popIfSame(actor, previous);
        }
        MutationReport.send(runtime, sender, actor, generation, "Undo (" + inverse + ")", result, filter);
    }

    private static QueryFilter idFilter(QueryFilter base, java.util.List<Long> ids) {
        return new QueryFilter(
                base.users(), base.sinceMillis(), base.untilMillis(),
                base.radius(), base.worldSel(),
                base.centerX(), base.centerY(), base.centerZ(),
                base.actions(), base.include(), base.exclude(),
                base.rolledBack(),
                false, false, base.verbose(), base.silent(), false,
                null,
                new LinkedHashSet<>(ids));
    }
}
