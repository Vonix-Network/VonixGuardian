package network.vonix.guardian.core.command;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconcilability of the rollback outcome line.
 *
 * <p>The owner's live evidence was:
 * <pre>
 *   scanned=962 planned=468 skipped=309
 *   PARTIAL affected=251
 * </pre>
 * {@code 309+468} is not {@code 962}, and 468 planned against 251 applied leaves
 * 217 steps unaccounted for. Neither gap was visible on the line the operator
 * saw. These tests pin the two properties that close it:
 * <ol>
 *   <li>the plan total is printed whenever it differs from what changed;</li>
 *   <li>a remainder that nothing explains gets its own sentence, in the plainest
 *       possible words, rather than being left as arithmetic for the operator.</li>
 * </ol>
 */
class MutationOutcomeReconciliationTest {

    private static final QueryFilter FILTER = QueryFilter.builder().sinceMillis(1L).build();

    @Test
    void affectedBelowPlannedAlwaysNamesThePlannedTotal() {
        // 251 applied out of a 468-step plan. The old line said "251 changed" and
        // nothing else, so 217 steps read as "fine".
        RollbackResult partial = result(468, 251, 0, RollbackResult.Status.PARTIAL,
            0, 0, 0, false, false);
        String line = MutationOutcomeFormatter.summaryLine("Rollback", partial);
        assertThat(line)
            .as("the planned total must appear when it differs from what changed: %s", line)
            .contains("251 changed")
            .contains("468 planned");
    }

    @Test
    void affectedEqualToPlannedDoesNotRepeatTheNumber() {
        RollbackResult done = result(468, 468, 0, RollbackResult.Status.SUCCESS,
            0, 0, 0, true, false);
        String line = MutationOutcomeFormatter.summaryLine("Rollback", done);
        assertThat(line)
            .contains("468 changed")
            .doesNotContain("468 planned");
    }

    @Test
    void unexplainedRemainderGetsItsOwnSentence() {
        // 468 planned, 251 applied, 0 skipped, 0 failed: 217 mutations vanished.
        RollbackResult orphan = result(468, 251, 0, RollbackResult.Status.PARTIAL,
            0, 0, 0, false, false);
        String gap = MutationOutcomeFormatter.unplannedGap(orphan);
        assertThat(gap)
            .as("a gap no counter explains must be stated, not left to subtraction")
            .isNotNull()
            .contains("217")
            .contains("468")
            .contains("neither applied nor reported as skipped or failed");
    }

    @Test
    void skippedRowsAreNotCountedTwiceWhenTheyExplainTheRemainder() {
        // 468 planned = 251 applied + 217 planned-skips. Fully reconciled.
        RollbackResult reconciled = result(468, 251, 217, RollbackResult.Status.SUCCESS,
            0, 0, 0, true, false);
        assertThat(MutationOutcomeFormatter.unplannedGap(reconciled))
            .as("a remainder already claimed by skipped rows is not a gap")
            .isNull();
    }

    @Test
    void failedAndCompensatedRowsCountAsAccounted() {
        RollbackResult withFailures = result(10, 7, 0, RollbackResult.Status.PARTIAL,
            2, 1, 0, false, false);
        assertThat(MutationOutcomeFormatter.unplannedGap(withFailures))
            .as("7 applied + 2 failed + 1 compensated accounts for all 10 planned steps")
            .isNull();
    }

    @Test
    void repairRequiredRowsCountAsAccounted() {
        RollbackResult withRepair = result(5, 4, 0, RollbackResult.Status.REPAIR_REQUIRED,
            0, 0, 1, false, false);
        assertThat(MutationOutcomeFormatter.unplannedGap(withRepair)).isNull();
    }

    @Test
    void gapLineAppearsInTheRenderedGroupAndIsSuppressedForPreviews() {
        RollbackResult orphan = result(468, 251, 0, RollbackResult.Status.PARTIAL,
            0, 0, 0, false, false);
        List<String> lines = MutationOutcomeFormatter.lines("Rollback", orphan, FILTER);
        assertThat(lines)
            .as("the gap must reach chat, not only the helper")
            .anyMatch(l -> l.contains("217") && l.contains("neither applied"));

        RollbackResult preview = result(468, 251, 0, RollbackResult.Status.SUCCESS,
            0, 0, 0, false, true);
        assertThat(MutationOutcomeFormatter.lines("Rollback", preview, FILTER))
            .as("a preview changes nothing, so it cannot leave a gap")
            .noneMatch(l -> l.contains("neither applied"));
    }

    @Test
    void skipReasonsAreNamedWithCounts() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("chat", 90);
        reasons.put("piston_move", 240);
        reasons.put("craft", 50);

        RollbackResult skipped = result(468, 251, 380, RollbackResult.Status.PARTIAL,
            0, 0, 0, false, false);
        String line = MutationOutcomeFormatter.summaryLine("Rollback", skipped, reasons);
        assertThat(line)
            .as("each reason must be named, largest first, with its own count: %s", line)
            .contains("skipped=380")
            .contains("piston_move 240")
            .contains("chat 90")
            .contains("craft 50");
        assertThat(line.indexOf("piston_move"))
            .as("largest reason first")
            .isLessThan(line.indexOf("chat"));
    }

    @Test
    void skipReasonsCollapseTheTailInsteadOfRunningOffTheLine() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("a", 5);
        reasons.put("b", 4);
        reasons.put("c", 3);
        reasons.put("d", 2);
        reasons.put("e", 1);
        String line = MutationOutcomeFormatter.summaryLine("Restore",
            result(20, 5, 15, RollbackResult.Status.SUCCESS, 0, 0, 0, true, false), reasons);
        assertThat(line)
            .contains("a 5")
            .contains("b 4")
            .contains("c 3")
            .contains("+2 others 3");
        assertThat(line)
            .as("five reasons must not all appear by name")
            .doesNotContain("d 2")
            .doesNotContain("e 1");
    }

    @Test
    void missingReasonDataStillReportsTheTotalHonestly() {
        // No reason map was supplied. The line must still say what it knows, and
        // must not invent a reason or an empty breakdown.
        String line = MutationOutcomeFormatter.summaryLine("Rollback",
            result(10, 4, 6, RollbackResult.Status.SUCCESS, 0, 0, 0, true, false), Map.of());
        assertThat(line)
            .contains("skipped=6")
            .doesNotContain("skipped=6 (")
            .doesNotContain("unknown");
    }

    @Test
    void countLineCarriesTheSameReasonSplit() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("chat", 12);
        String line = MutationOutcomeFormatter.countLine("Rollback",
            result(30, 18, 12, RollbackResult.Status.SUCCESS, 0, 0, 0, true, false), reasons);
        assertThat(line)
            .contains("planned=30")
            .contains("skipped=12")
            .contains("chat 12")
            .contains("Nothing was changed.");
    }

    @Test
    void reasonsOfGroupsTypesLargestFirstAndLowercases() {
        Map<String, Integer> grouped = MutationOutcomeFormatter.reasonsOf(
            List.of("CHAT", "PISTON_EXTEND", "CHAT", "CHAT", "PISTON_EXTEND", "CLICK"));
        assertThat(grouped).containsEntry("chat", 3).containsEntry("piston_extend", 2)
            .containsEntry("click", 1);
        assertThat(grouped.keySet()).containsExactly("chat", "piston_extend", "click");

        assertThat(MutationOutcomeFormatter.reasonsOf(null)).isEmpty();
        assertThat(MutationOutcomeFormatter.reasonsOf(List.of())).isEmpty();
        assertThat(MutationOutcomeFormatter.reasonsOf(java.util.Arrays.asList("CHAT", null)))
            .containsEntry("chat", 1)
            .containsEntry("unknown", 1);
    }

    @Test
    void sortByCountDropsZeroAndNegativeCounts() {
        Map<String, Integer> raw = new LinkedHashMap<>();
        raw.put("zero", 0);
        raw.put("negative", -3);
        raw.put("real", 4);
        Map<String, Integer> sorted = MutationOutcomeFormatter.sortByCount(raw);
        assertThat(sorted)
            .as("a reason with no rows is not a reason and must not be named")
            .hasSize(1)
            .containsEntry("real", 4);
    }

    @Test
    void nullVerbNeverPrintsTheWordNull() {
        RollbackResult done = result(2, 2, 0, RollbackResult.Status.SUCCESS, 0, 0, 0, true, false);
        assertThat(MutationOutcomeFormatter.summaryLine(null, done))
            .as("a null verb must not become \"null changed\"")
            .doesNotContain("null")
            .contains("Mutation");
        assertThat(MutationOutcomeFormatter.summaryLine("   ", done))
            .doesNotContain("null")
            .contains("Mutation");
        assertThat(MutationOutcomeFormatter.countLine(null, done)).doesNotContain("null");
        assertThat(MutationOutcomeFormatter.verboseLine(null, done)).doesNotContain("null");
    }

    @Test
    void extraLinesAreIndentedUnderTheSummaryNotBrandedAgain() {
        // The group is one message. Branding each line is the same mistake as
        // branding all 62 status lines.
        QueryFilter verbose = QueryFilter.builder().sinceMillis(1L).verbose(true).build();
        List<String> lines = MutationOutcomeFormatter.lines("Rollback",
            result(5, 3, 2, RollbackResult.Status.SUCCESS, 0, 0, 0, true, false), verbose);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        assertThat(lines.get(0)).startsWith(MutationOutcomeFormatter.BRAND);
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i))
                .as("continuation line %d must not repeat the brand: [%s]", i, lines.get(i))
                .doesNotContain(MutationOutcomeFormatter.BRAND);
            assertThat(lines.get(i))
                .as("continuation line %d must be indented under the summary", i)
                .startsWith(MutationOutcomeFormatter.CONTINUATION);
        }
    }

    /**
     * Build a result whose planned step count and applied count are independent.
     *
     * <p>The public record derives {@code affectedCount()} from
     * {@code affectedIds}, so ids are generated to the requested applied count.
     */
    private static RollbackResult result(int planned, int applied, int skipped,
                                         RollbackResult.Status status,
                                         int failed, int compensated, int repair,
                                         boolean batchClosed, boolean preview) {
        List<Long> affectedIds = new java.util.ArrayList<>(applied);
        for (int i = 0; i < applied; i++) {
            affectedIds.add((long) (i + 1));
        }
        List<Long> skippedIds = new java.util.ArrayList<>(skipped);
        for (int i = 0; i < skipped; i++) {
            skippedIds.add((long) (10_000 + i));
        }
        return new RollbackResult(null, RollbackResult.Mode.ROLLBACK, preview,
            affectedIds, skippedIds, planned, preview ? 0 : applied, FILTER,
            status, preview ? 0L : 42L, failed, compensated, repair, batchClosed);
    }
}
