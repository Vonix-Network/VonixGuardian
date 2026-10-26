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
import network.vonix.guardian.core.rollback.RollbackCancelledException;
import network.vonix.guardian.core.rollback.RollbackEngine;
import network.vonix.guardian.core.rollback.RollbackLimitExceededException;
import network.vonix.guardian.core.rollback.RollbackOptions;
import network.vonix.guardian.core.rollback.RollbackPlan;
import network.vonix.guardian.core.rollback.RollbackResult;
import network.vonix.guardian.hytale.world.WriteVerifier;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /guardian rollback} and {@code /guardian restore}. Planning and
 * execution run on the command worker. {@code #preview} and {@code #count}
 * do not change the world and do not push undo.
 */
final class FilterMutationCommand extends GuardianSubcommand {

    private final RequiredArg<String> filterArg;
    private final RollbackResult.Mode mode;
    private final CommandRuntime.QueryKind kind;
    private final String verb;

    FilterMutationCommand(CommandRuntime runtime, Logger logger, boolean restore) {
        super(restore ? CommandSpec.RESTORE : CommandSpec.ROLLBACK,
                restore ? "Re-apply actions previously undone by rollback."
                        : "Undo logged actions matching the filter.",
                true, runtime, logger,
                CommandSpec.permissionNode(restore ? CommandSpec.RESTORE : CommandSpec.ROLLBACK),
                true);
        addAliases(restore ? "rs" : "rb");
        filterArg = withRequiredArg("filter", "Filter tokens such as t:1h u:name r:10.", ArgTypes.GREEDY_STRING);
        mode = restore ? RollbackResult.Mode.RESTORE : RollbackResult.Mode.ROLLBACK;
        kind = restore ? CommandRuntime.QueryKind.RESTORE : CommandRuntime.QueryKind.ROLLBACK;
        verb = restore ? "Restore" : "Rollback";
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        if (!admit(context)) {
            return done();
        }
        String raw = context.get(filterArg);
        if (raw == null) {
            runtime.reply(context, runtime.error("[VonixGuardian] A filter is required. Nothing was changed."));
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
        CommandRuntime.Prepared prepared = runtime.prepare(
                guardian, actor, runtime.withoutConfirmToken(raw), kind);
        if (prepared.failed()) {
            runtime.reply(context, runtime.error("[VonixGuardian] " + prepared.error() + " Nothing was changed."));
            return done();
        }
        CommandSender sender = context.sender();
        long generation = runtime.beginChat(actor.uuid());
        runtime.submit(sender, () -> run(guardian, sender, actor.uuid(), generation, prepared.filter()));
        return done();
    }

    private void run(Guardian guardian, CommandSender sender, UUID actor, long generation, QueryFilter filter) {
        RollbackEngine engine = guardian.rollbackEngine();
        RollbackOptions options = MutationReport.options(runtime, sender, actor, generation);
        RollbackPlan plan;
        try {
            plan = engine.plan(filter, mode, actor, options);
        } catch (IllegalArgumentException | RollbackLimitExceededException | RollbackCancelledException ex) {
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] " + verb + " refused: " + reason(ex) + " Nothing was changed."));
            return;
        } catch (Exception ex) {
            logger.warn(Guardian.MARKER, verb + " planning failed", ex);
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] " + verb + " planning failed: " + reason(ex) + " Nothing was changed."));
            return;
        }
        if (filter.countOnly()) {
            RollbackResult counted = new RollbackResult(actor, mode, true,
                    plan.actionIds(), plan.skippedIds(), plan.plannedSteps(), 0, filter);
            MutationReport.send(runtime, sender, actor, generation, verb, counted, filter);
            return;
        }
        boolean preview = filter.preview();
        RollbackResult result;
        try {
            result = engine.execute(plan, preview);
        } catch (Exception ex) {
            logger.warn(Guardian.MARKER, verb + " execution failed", ex);
            runtime.deliver(sender, actor, generation, runtime.error(
                    "[VonixGuardian] " + verb + " failed without a result. The world may have changed. Nothing was added to undo."));
            return;
        }
        // Durable record: an operator reporting "some blocks did not come back" needs the
        // exact counts in the server log, not a screenshot of the chat message.
        logger.info(Guardian.MARKER,
                "{} result: preview={} success={} mutatedWorld={} affected={} applied={} skipped={} filter={}",
                verb, result.preview(), result.isSuccess(), result.mutatedWorld(),
                result.affectedCount(), result.appliedCount(), result.skippedCount(), filter);
        if (!result.preview() && result.mutatedWorld()) {
            // Second pair of eyes: re-read every position this batch intended to write and
            // repair anything that did not stick. The engine's own counts cannot see a write
            // that was accepted and then reverted later in the tick.
            WriteVerifier.Report verification = WriteVerifier.converge(
                    runtime.services().world(), runtime.services().mainThread());
            logger.info(Guardian.MARKER, "{} verification: {}", verb, verification.summary());
            if (!verification.clean()) {
                runtime.deliver(sender, actor, generation, runtime.warning(
                        "[VonixGuardian] Post-" + verb + " convergence: " + verification.summary()));
            }
            try {
                guardian.undoStack().push(actor, result);
            } catch (RuntimeException ex) {
                logger.warn(Guardian.MARKER, "Undo history was not updated after " + verb, ex);
                runtime.deliver(sender, actor, generation, runtime.warning(
                        "[VonixGuardian] The world change was applied, but undo history was not updated."));
            }
        }
        MutationReport.send(runtime, sender, actor, generation, verb, result, filter);
    }
}
