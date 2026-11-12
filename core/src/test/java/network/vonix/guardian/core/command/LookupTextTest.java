package network.vonix.guardian.core.command;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LookupTextTest {

    @Test
    void rowNamesWorldAmountAndClockTime() {
        long when = Instant.parse("2026-11-12T14:02:00Z").toEpochMilli();
        Action row = new Action(
            17L, when, ActionType.CONTAINER_WITHDRAW, UUID.randomUUID(), "Notch",
            "minecraft:the_nether", 10, 64, -20,
            "minecraft:diamond", null, 64, false, null,
            null, null, null, null, null, null, null, null, null, 4);
        String plain = LookupText.plain(row, when + 3 * 60_000L, false);
        assertThat(plain)
            .contains("3m ago")
            .contains("12 Nov 14:02 UTC")
            .contains("Notch")
            .contains("took")
            .contains("64× diamond")
            .contains("the_nether 10, 64, -20")
            .doesNotContain("minecraft:diamond");
        LookupText.Piece where = LookupText.line(row, when, false).stream()
            .filter(p -> "where".equals(p.role())).findFirst().orElseThrow();
        assertThat(where.suggest()).isEqualTo("/vg tp minecraft:the_nether 10 64 -20");
        LookupText.Piece what = LookupText.line(row, when, false).stream()
            .filter(p -> "what".equals(p.role())).findFirst().orElseThrow();
        assertThat(what.copy()).isEqualTo("minecraft:diamond");
        assertThat(what.hover()).contains("slot 4");
    }

    @Test
    void verbosePrintsSignSlotAndSourceOnTheLine() {
        Action row = new Action(
            3L, 1_000L, ActionType.SIGN, null, "Notch",
            "minecraft:overworld", 1, 2, 3, "hello there", null, 0, true, "player",
            "front", "red", true, null, null, null, null, null, 9L, null);
        String quiet = LookupText.plain(row, 1_000L, false);
        String loud = LookupText.plain(row, 1_000L, true);
        assertThat(quiet).contains("front").contains("red").contains("waxed").contains("rolled back");
        assertThat(loud).contains("front, red, waxed").contains("player").contains("pair 9");
        assertThat(loud).doesNotContain("slot");
    }

    @Test
    void longTargetIsCutOnTheLineAndKeptForCopy() {
        String raw = "minecraft:" + "n".repeat(80);
        Action row = new Action(
            1L, 0L, ActionType.CHAT, null, "Notch",
            "minecraft:overworld", 0, 0, 0, raw, null, 0, false, null);
        String plain = LookupText.plain(row, 0L, false);
        assertThat(plain).contains("…").doesNotContain(raw);
        assertThat(plain).doesNotContain("0, 0, 0");
        LookupText.Piece what = LookupText.line(row, 0L, false).stream()
            .filter(p -> "what".equals(p.role())).findFirst().orElseThrow();
        assertThat(what.copy()).isEqualTo(raw);
    }

    @Test
    void nullFailureIsNotTheWordNull() {
        assertThat(OperatorText.detail(new RuntimeException())).isEqualTo("RuntimeException");
        assertThat(OperatorText.detail(new IllegalStateException("disk full"))).isEqualTo("disk full");
        assertThat(OperatorText.detail(null)).isEqualTo("unknown error");
        assertThat(OperatorText.detail(new Exception("null"))).doesNotContain("null");
    }

    @Test
    void previewCountAndRealChangeReadAsDifferentSentences() {
        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();
        RollbackResult preview = new RollbackResult(null, RollbackResult.Mode.ROLLBACK, true,
            List.of(1L), List.of(), 4, 0, filter);
        RollbackResult done = new RollbackResult(null, RollbackResult.Mode.ROLLBACK, false,
            List.of(1L, 2L), List.of(3L), 3, 2, filter,
            RollbackResult.Status.SUCCESS, 17L, 0, 0, 0, true);
        RollbackResult repair = new RollbackResult(null, RollbackResult.Mode.ROLLBACK, false,
            List.of(1L), List.of(), 2, 1, filter,
            RollbackResult.Status.REPAIR_REQUIRED, 18L, 1, 0, 1, false);
        String previewLine = MutationOutcomeFormatter.summaryLine("Rollback", preview);
        String doneLine = MutationOutcomeFormatter.summaryLine("Rollback", done);
        String repairLine = MutationOutcomeFormatter.summaryLine("Rollback", repair);
        String countLine = MutationOutcomeFormatter.countLine("Rollback", done);
        assertThat(previewLine)
            .contains("(preview)")
            .contains("The world was not changed")
            .doesNotContain("affected=");
        assertThat(doneLine)
            .contains("Done — SUCCESS")
            .contains("2 changed")
            .contains("skipped=1")
            .contains("Batch 17 is closed")
            .doesNotContain("affected=")
            .doesNotContain("failed=");
        assertThat(repairLine)
            .contains("Needs repair — REPAIR_REQUIRED")
            .contains("failed=1")
            .contains("Batch 18 is still open");
        assertThat(countLine).contains("count:").contains("Nothing was changed.").doesNotContain("(preview)");
        assertThat(previewLine).isNotEqualTo(doneLine);
    }
}
