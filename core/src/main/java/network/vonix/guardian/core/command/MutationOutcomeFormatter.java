package network.vonix.guardian.core.command;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared user-visible vocabulary for rollback, restore, undo, and preview apply.
 * Loader command adapters print these lines; they must not invent a later
 * generic failure for the same request.
 */
public final class MutationOutcomeFormatter {

    private MutationOutcomeFormatter() {}

    /**
     * Chat lines for one authoritative result. Empty when {@code #silent} and
     * the status is {@link RollbackResult.Status#SUCCESS}.
     */
    public static List<String> lines(String verb, RollbackResult result, QueryFilter filter) {
        boolean silent = filter != null && filter.silent();
        boolean verbose = filter != null && filter.verbose();
        boolean countOnly = filter != null && filter.countOnly();
        if (countOnly) {
            return List.of(countLine(verb, result));
        }
        if (silent && result.isSuccess() && !result.preview()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        out.add(summaryLine(verb, result));
        if (verbose) {
            out.add(verboseLine(verb, result));
        }
        if (!silent && !result.preview() && result.appliedCount() == 0
                && result.status() == RollbackResult.Status.SUCCESS) {
            out.add("[VonixGuardian] " + verb
                + " found 0 matching actions. Explosions record their blast CENTER — try r:20+ or move to the source of the damage.");
        }
        return List.copyOf(out);
    }

    public static String summaryLine(String verb, RollbackResult result) {
        String preview = result.preview() ? "(preview) " : "";
        return "[VonixGuardian] " + verb + " " + preview
            + result.status()
            + " affected=" + result.affectedCount()
            + " planned=" + result.plannedSteps()
            + " skipped=" + result.skippedCount()
            + " failed=" + result.failedCount()
            + " compensated=" + result.compensatedCount()
            + " repairRequired=" + result.repairRequiredCount()
            + " batchId=" + result.batchId()
            + (result.preview() || result.batchId() == 0L ? "" : " closed=" + result.batchClosed());
    }

    public static String countLine(String verb, RollbackResult result) {
        return "[VonixGuardian] " + verb + " count: planned=" + result.plannedSteps()
            + " skipped=" + result.skippedCount();
    }

    public static String verboseLine(String verb, RollbackResult result) {
        return "[VonixGuardian] " + verb + " verbose appliedIds=" + result.affectedIds().size()
            + " skippedIds=" + result.skippedIds().size()
            + " dispatched=" + result.dispatchedSteps();
    }

    public static boolean isErrorTone(RollbackResult result) {
        return result.status() != RollbackResult.Status.SUCCESS;
    }
}
