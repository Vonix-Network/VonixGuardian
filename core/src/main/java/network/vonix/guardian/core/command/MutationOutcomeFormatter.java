package network.vonix.guardian.core.command;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared user-visible vocabulary for rollback, restore, undo, and preview apply.
 * A count, a preview, and a real change do not share one sentence.
 * Zero counters are left off the line. The status is a word, then the name.
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
                + " found 0 matching actions. Explosions record their blast center. Try r:20+ or stand at the source of the damage.");
        }
        return List.copyOf(out);
    }

    public static String summaryLine(String verb, RollbackResult result) {
        StringBuilder line = new StringBuilder();
        line.append("[VonixGuardian] ").append(verb).append(' ');
        if (result.preview()) {
            line.append("(preview) ").append(stateWord(result.status()))
                .append(" — ").append(result.status())
                .append(". ").append(result.affectedCount()).append(" would change");
        } else {
            line.append(stateWord(result.status()))
                .append(" — ").append(result.status())
                .append(". ").append(result.affectedCount()).append(" changed");
        }
        if (result.skippedCount() > 0) {
            line.append(", skipped=").append(result.skippedCount());
        }
        if (result.failedCount() > 0) {
            line.append(", failed=").append(result.failedCount());
        }
        if (result.compensatedCount() > 0) {
            line.append(", ").append(result.compensatedCount()).append(" compensated");
        }
        if (result.repairRequiredCount() > 0) {
            line.append(", ").append(result.repairRequiredCount()).append(" need repair");
        }
        if (!result.preview() && result.batchId() != 0L) {
            line.append(". Batch ").append(result.batchId());
            if (result.batchClosed()) {
                line.append(" is closed");
            } else {
                line.append(" is still open. Use /vg batch show ").append(result.batchId());
            }
        }
        if (result.preview()) {
            line.append(". The world was not changed");
        }
        line.append('.');
        return line.toString();
    }

    public static String countLine(String verb, RollbackResult result) {
        return "[VonixGuardian] " + verb + " count: planned=" + result.plannedSteps()
            + " skipped=" + result.skippedCount()
            + ". Nothing was changed.";
    }

    public static String verboseLine(String verb, RollbackResult result) {
        return "[VonixGuardian] " + verb + " verbose: applied " + result.affectedIds().size()
            + ", skipped " + result.skippedIds().size()
            + ", world changes " + result.dispatchedSteps() + ".";
    }

    public static boolean isErrorTone(RollbackResult result) {
        return result.status() != RollbackResult.Status.SUCCESS;
    }

    private static String stateWord(RollbackResult.Status status) {
        return switch (status) {
            case SUCCESS -> "Done";
            case PARTIAL -> "Partly done";
            case FAILED -> "Failed";
            case COMPENSATED -> "Compensated";
            case REPAIR_REQUIRED -> "Needs repair";
        };
    }
}
