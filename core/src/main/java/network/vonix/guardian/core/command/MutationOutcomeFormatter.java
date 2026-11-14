package network.vonix.guardian.core.command;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Shared user-visible vocabulary for rollback, restore, undo, and preview apply.
 * A count, a preview, and a real change do not share one sentence.
 * Zero counters are left off the line. The status is a word, then the name.
 *
 * <p><b>Reconcilability.</b> A rollback result is a statement about a specific
 * number of planned world mutations, so every line that reports an outcome also
 * reports the plan total when the two differ ("251 changed of 468 planned").
 * An operator who reads {@code affected=251} against a request that planned 468
 * has 217 mutations they cannot account for; the line has to make that gap
 * visible rather than leave it to be discovered. {@link #unplannedGap} names
 * the shortfall explicitly when nothing else explains it.</p>
 *
 * <p><b>Skipped reasons.</b> {@code skipped=380} is one number standing in for
 * a dozen different refusals ({@link network.vonix.guardian.core.action.ActionType}
 * families that cannot be reversed: chat, piston moves, unsupported restores,
 * rows deduplicated by position, dispatch-time refusals). Pass a per-reason
 * map to {@link #lines(String, RollbackResult, QueryFilter, Map)} and the line
 * names them; without one the line still reports the total, which is the most
 * it can honestly say.</p>
 *
 * <p><b>Branding.</b> {@code [VonixGuardian]} appears on the first line of a
 * group only. Repeating it on every continuation line spent a third of the
 * chat width saying the same thing, which is the same defect as the status
 * report branding all 62 of its lines.</p>
 */
public final class MutationOutcomeFormatter {

    /** Brand prefix. Applied to the first line of a group, not every line. */
    public static final String BRAND = "[VonixGuardian] ";

    /** Indent for continuation lines, aligned under the text after the brand. */
    static final String CONTINUATION = "  ";

    /** How many distinct skip reasons are named before the tail is summed. */
    private static final int MAX_REASONS_SHOWN = 3;

    private MutationOutcomeFormatter() {}

    /**
     * Chat lines for one authoritative result. Empty when {@code #silent} and
     * the status is {@link RollbackResult.Status#SUCCESS}.
     *
     * <p>Reports the skip <em>total</em> and the plan total. The per-reason
     * split needs a reason histogram, which this overload does not have; call
     * {@link #lines(String, RollbackResult, QueryFilter, Map)} to name the
     * reasons. Reporting one opaque number is still better than the old line,
     * which reported neither the total against the plan nor any split, but it
     * is not the whole fix.</p>
     */
    public static List<String> lines(String verb, RollbackResult result, QueryFilter filter) {
        return lines(verb, result, filter, Map.of());
    }

    /**
     * Chat lines for one authoritative result, naming each distinct reason rows
     * were skipped.
     *
     * @param skippedByReason human label (e.g. {@code "chat"}) to row count;
     *                        null / empty means the reason split is unknown and
     *                        the line reports only the total
     */
    public static List<String> lines(String verb, RollbackResult result, QueryFilter filter,
                                     Map<String, Integer> skippedByReason) {
        Objects.requireNonNull(result, "result");
        String name = verbOrFallback(verb);
        boolean silent = filter != null && filter.silent();
        boolean verbose = filter != null && filter.verbose();
        boolean countOnly = filter != null && filter.countOnly();
        if (countOnly) {
            return List.of(countLine(name, result, skippedByReason));
        }
        if (silent && result.isSuccess() && !result.preview()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        out.add(summaryLine(name, result, skippedByReason));
        if (verbose) {
            out.add(CONTINUATION + verboseLine(name, result));
        }
        if (!silent && !result.preview() && result.appliedCount() == 0
                && result.status() == RollbackResult.Status.SUCCESS) {
            out.add(CONTINUATION + name
                + " found 0 matching actions. Explosions record their blast center."
                + " Try r:20+ or stand at the source of the damage.");
        }
        String gap = unplannedGap(result);
        if (gap != null && !silent && !result.preview()) {
            out.add(CONTINUATION + gap);
        }
        return List.copyOf(out);
    }

    public static String summaryLine(String verb, RollbackResult result) {
        return summaryLine(verb, result, Map.of());
    }

    public static String summaryLine(String verb, RollbackResult result,
                                     Map<String, Integer> skippedByReason) {
        Objects.requireNonNull(result, "result");
        StringBuilder line = new StringBuilder();
        line.append(BRAND).append(verbOrFallback(verb)).append(' ');
        if (result.preview()) {
            line.append("(preview) ").append(stateWord(result.status()))
                .append(" — ").append(result.status())
                .append(". ").append(result.affectedCount()).append(" would change");
        } else {
            line.append(stateWord(result.status()))
                .append(" — ").append(result.status())
                .append(". ").append(result.affectedCount()).append(" changed");
        }
        // The plan total, whenever it differs from what actually changed. Without
        // it "251 changed" on a 468-step plan reads as "the other 217 were fine".
        if (result.plannedSteps() != result.affectedCount()) {
            line.append(" (of ").append(result.plannedSteps()).append(" planned)");
        }
        if (result.skippedCount() > 0) {
            line.append(", skipped=").append(result.skippedCount());
            String breakdown = reasonBreakdown(skippedByReason);
            if (!breakdown.isEmpty()) {
                line.append(" (").append(breakdown).append(')');
            }
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
            // #preview used to be described as painting the plan into the world.
            // It never did: no ghost blocks, no markers, no block-update packet —
            // it plans, counts, and holds the exact action ids for /vg apply.
            // Operators went looking for glass that was never drawn, so the line
            // says what actually happened and names the two ways out.
            line.append(". The world was not changed and no blocks were drawn in it."
                + " /vg apply runs this plan, /vg cancel drops it");
        }
        line.append('.');
        return line.toString();
    }

    public static String countLine(String verb, RollbackResult result) {
        return countLine(verb, result, Map.of());
    }

    public static String countLine(String verb, RollbackResult result,
                                   Map<String, Integer> skippedByReason) {
        Objects.requireNonNull(result, "result");
        StringBuilder line = new StringBuilder();
        line.append(BRAND).append(verbOrFallback(verb))
            .append(" count: planned=").append(result.plannedSteps())
            .append(" skipped=").append(result.skippedCount());
        String breakdown = reasonBreakdown(skippedByReason);
        if (!breakdown.isEmpty()) {
            line.append(" (").append(breakdown).append(')');
        }
        line.append(". Nothing was changed.");
        return line.toString();
    }

    public static String verboseLine(String verb, RollbackResult result) {
        Objects.requireNonNull(result, "result");
        // No brand: this is a continuation line under the summary, and repeating
        // the product name on every line is the defect the report flood had.
        return verbOrFallback(verb) + " verbose: applied " + result.affectedIds().size()
            + ", skipped " + result.skippedIds().size()
            + ", world changes " + result.dispatchedSteps() + ".";
    }

    public static boolean isErrorTone(RollbackResult result) {
        return result.status() != RollbackResult.Status.SUCCESS;
    }

    /**
     * The number of planned world mutations that no counter accounts for:
     * neither applied, nor failed, nor compensated, nor excluded by the plan.
     *
     * <p>This is the arithmetic the owner's own evidence failed:
     * {@code planned=468 applied=251} with no further explanation leaves 217
     * mutations unaccounted for. When the remainder is positive and nothing
     * else claims those rows, the operator is told in a sentence rather than
     * left to subtract.</p>
     *
     * @return a sentence naming the gap, or {@code null} when the numbers
     *         reconcile or the remainder is already explained by skipped rows
     */
    public static String unplannedGap(RollbackResult result) {
        int accounted = result.affectedCount()
                + result.failedCount()
                + result.compensatedCount()
                + result.repairRequiredCount()
                + result.skippedCount();
        int unexplained = result.plannedSteps() - accounted;
        if (unexplained <= 0) {
            return null;
        }
        return unexplained + " of " + result.plannedSteps()
            + " planned changes were neither applied nor reported as skipped or failed."
            + " Treat the world as partly changed and check the server log before re-running.";
    }

    /**
     * Group a list of skip reasons into a {@code reason -> count} map, sorted
     * largest first. Accepts raw type names ({@code "CHAT"}) and normalises
     * them to lower case for display.
     *
     * @param types one entry per skipped row; entries may be null
     * @return sorted, unmodifiable count map; empty when no rows were given
     */
    public static Map<String, Integer> reasonsOf(Iterable<String> types) {
        if (types == null) {
            return Map.of();
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String raw : types) {
            String key = raw == null || raw.isBlank()
                    ? "unknown"
                    : raw.toLowerCase(Locale.ROOT);
            counts.merge(key, 1, Integer::sum);
        }
        return sortByCount(counts);
    }

    /** Sort a reason->count map largest first, dropping zero and negative counts. */
    public static Map<String, Integer> sortByCount(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                entries.add(e);
            }
        }
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed());
        Map<String, Integer> ordered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries) {
            ordered.put(e.getKey() == null || e.getKey().isBlank() ? "unknown" : e.getKey(),
                    e.getValue());
        }
        return ordered;
    }

    /**
     * {@code "chat 300, piston 60, +2 others 20"}, or empty when there is
     * nothing to name. The tail is summed rather than listed so the line stays
     * inside one chat line at default GUI scale.
     */
    private static String reasonBreakdown(Map<String, Integer> skippedByReason) {
        Map<String, Integer> ordered = sortByCount(skippedByReason);
        if (ordered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int tail = 0;
        int tailReasons = 0;
        for (Map.Entry<String, Integer> e : ordered.entrySet()) {
            if (shown < MAX_REASONS_SHOWN) {
                if (shown > 0) sb.append(", ");
                sb.append(e.getKey()).append(' ').append(e.getValue());
                shown++;
            } else {
                tail += e.getValue();
                tailReasons++;
            }
        }
        if (tailReasons > 0) {
            sb.append(", +").append(tailReasons).append(" others ").append(tail);
        }
        return sb.toString();
    }

    /**
     * The verb as it will be printed. A command layer that passes {@code null}
     * must not produce "null changed" — the same defect as an error path that
     * prints {@code null}.
     */
    static String verbOrFallback(String verb) {
        return verb == null || verb.isBlank() ? "Mutation" : verb.trim();
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
