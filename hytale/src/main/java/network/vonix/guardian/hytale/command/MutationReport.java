/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import network.vonix.guardian.core.command.MutationOutcomeFormatter;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackOptions;
import network.vonix.guardian.core.rollback.RollbackProgress;
import network.vonix.guardian.core.rollback.RollbackResult;

import java.util.UUID;

/** Prints {@link MutationOutcomeFormatter} lines and planning progress. */
final class MutationReport {

    private MutationReport() {
    }

    static RollbackOptions options(CommandRuntime runtime, CommandSender sender, UUID actor, long generation) {
        return new RollbackOptions(
                RollbackOptions.DEFAULT_PAGE_SIZE,
                RollbackOptions.DEFAULT_MAX_SCANNED_ACTIONS,
                RollbackOptions.DEFAULT_MAX_PLANNED_STEPS,
                () -> Thread.currentThread().isInterrupted(),
                progress -> {
                    if (!shouldReport(progress)) {
                        return;
                    }
                    runtime.deliver(sender, actor, generation, runtime.muted(
                            "[VonixGuardian] Planning: scanned=" + progress.scannedActions()
                                    + " planned=" + progress.plannedSteps()
                                    + " skipped=" + progress.skippedActions()));
                });
    }

    static void send(CommandRuntime runtime, CommandSender sender, UUID actor, long generation,
                     String verb, RollbackResult result, QueryFilter filter) {
        for (String line : MutationOutcomeFormatter.lines(verb, result, filter)) {
            runtime.deliver(sender, actor, generation, tone(runtime, result, line));
        }
    }

    private static boolean shouldReport(RollbackProgress progress) {
        return progress.scanLimitReached()
                || progress.plannedLimitReached()
                || progress.cancelled()
                || progress.pagesFetched() == 1
                || progress.pagesFetched() % 10 == 0;
    }

    private static Message tone(CommandRuntime runtime, RollbackResult result, String line) {
        if (result.status() == RollbackResult.Status.FAILED) {
            return runtime.error(line);
        }
        if (result.status() == RollbackResult.Status.SUCCESS) {
            return runtime.success(line);
        }
        return runtime.warning(line);
    }
}
