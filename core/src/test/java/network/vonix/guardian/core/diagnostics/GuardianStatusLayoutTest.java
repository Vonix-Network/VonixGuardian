package network.vonix.guardian.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readability and honesty guarantees of the {@code /vg status} report that can
 * be proven without booting a {@link network.vonix.guardian.core.Guardian}.
 *
 * <p>Each test here pins a defect the report actually had:
 * <ol>
 *   <li>the value column moved because labels were hand-padded to six widths;</li>
 *   <li>the report ran past a screen of chat on a healthy server;</li>
 *   <li>the {@code health} row claimed a check it never performed;</li>
 *   <li>a {@code null} value printed the word "null".</li>
 * </ol>
 */
class GuardianStatusLayoutTest {

    @Test
    void everyRowPutsItsValueInTheSameColumn() {
        // The rows the report is built from, including the longest and shortest
        // labels it uses. Every value offset must agree.
        List<String[]> rows = List.of(
            new String[] {"plugin", "0.0.0"},
            new String[] {"api", "v1"},
            new String[] {"theme", "aqua"},
            new String[] {"backend", "sqlite"},
            new String[] {"schema", "v9 (current)"},
            new String[] {"reachable", "yes (SELECT 1 answered)"},
            new String[] {"consumer", "running"},
            new String[] {"maintenance", "normal"},
            new String[] {"depth", "0 of 50000"},
            new String[] {"counters", "submitted=0 gated=0 dropped=0"},
            new String[] {"durability", "sinkDrops=0 quarantined=0"},
            new String[] {"mixin events", "on, alloc 0.00/s"},
            new String[] {"coalescer", "off (window 0)"},
            new String[] {"open batches", "none"},
            new String[] {"repair required", "none"},
            new String[] {"hooks", "none registered, 2 internal"},
            new String[] {"auto-purge", "off (0 = unbounded)"},
            new String[] {"permissions", "default-op 3, 0 per-node overrides, LuckPerms on"},
            new String[] {"worlds", "no overrides"},
            new String[] {"logged types", "BLOCK_PLACE=12"},
            new String[] {"dropped types", "SPLASH_POTION=1"}
        );

        int expectedColumn = -1;
        for (String[] row : rows) {
            String rendered = renderOne(row[0], row[1]);
            int column = rendered.indexOf(row[1]);
            assertThat(column)
                .as("value of \"%s\" must start at a fixed column, got line [%s]", row[0], rendered)
                .isGreaterThan(0);
            if (expectedColumn < 0) {
                expectedColumn = column;
            } else {
                assertThat(column)
                    .as("row \"%s\" value column drifted from %d: [%s]", row[0], expectedColumn, rendered)
                    .isEqualTo(expectedColumn);
            }
        }
    }

    @Test
    void labelColumnIsWideEnoughForTheLongestLabel() {
        // "repair required" is 15 characters and is the longest label the report
        // uses. If LABEL_WIDTH were smaller, padRight would stop padding and this
        // row's value would start a column later than every other row.
        assertThat(GuardianStatus.LABEL_WIDTH)
            .as("LABEL_WIDTH must fit the longest label or the column jitters")
            .isGreaterThanOrEqualTo("repair required".length());
        assertThat(renderOne("repair required", "none").indexOf("none"))
            .as("longest label must still land on the shared value column")
            .isEqualTo(renderOne("depth", "none").indexOf("none"));
    }

    @Test
    void nullAndBlankValuesNeverRenderTheWordNull() {
        assertThat(renderOne("theme", null))
            .contains(GuardianStatus.NOT_SET)
            .doesNotContain("null");
        assertThat(renderOne("theme", ""))
            .contains(GuardianStatus.NOT_SET)
            .doesNotContain("null");
        assertThat(renderOne("theme", "   "))
            .contains(GuardianStatus.NOT_SET);
        assertThat(GuardianStatus.blankTo(null, "fallback")).isEqualTo("fallback");
        assertThat(GuardianStatus.blankTo("", "fallback")).isEqualTo("fallback");
        assertThat(GuardianStatus.blankTo("value", "fallback")).isEqualTo("value");
    }

    @Test
    void aThrownAccessorNamesItsTypeInsteadOfNull() {
        assertThat(GuardianStatus.errorName(new IllegalStateException("disk full")))
            .isEqualTo("error (IllegalStateException)")
            .doesNotContain("null");
        // A message is deliberately not included: the row is a label, and a
        // multi-line exception message would break the alignment it guarantees.
        assertThat(GuardianStatus.errorName(new IllegalStateException("disk full")))
            .doesNotContain("disk full");
        assertThat(GuardianStatus.errorName(new NullPointerException()))
            .isEqualTo("error (NullPointerException)");
        assertThat(GuardianStatus.errorName(null)).isEqualTo("error (unknown)");
    }

    @Test
    void histogramNamesTopEntriesAndSumsTheTail() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("A", 9L);
        map.put("B", 7L);
        map.put("C", 5L);
        map.put("D", 3L);
        map.put("E", 2L);
        map.put("F", 1L);
        map.put("G", 1L);
        String rendered = GuardianStatus.formatHistogram(map);
        assertThat(rendered)
            .startsWith("A=9, B=7, C=5, D=3")
            .contains("(+3 others=4)");
    }

    @Test
    void histogramSurvivesNullKeysAndNullCounts() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(null, 2L);
        map.put("B", null);
        String rendered = GuardianStatus.formatHistogram(map);
        assertThat(rendered).contains("unknown=2").doesNotContain("null");
        assertThat(GuardianStatus.formatHistogram(Map.of())).isEmpty();
        assertThat(GuardianStatus.formatHistogram(null)).isEmpty();
    }

    @Test
    void schemaRowNamesDriftInBothDirections() {
        assertThat(GuardianStatus.schemaState(9, 9))
            .isEqualTo("v9 (current)")
            .doesNotContain("BEHIND")
            .doesNotContain("AHEAD");

        assertThat(GuardianStatus.schemaState(7, 9))
            .contains("v7 BEHIND v9")
            .contains("restart to apply migrations");

        assertThat(GuardianStatus.schemaState(11, 9))
            .contains("v11 AHEAD of this jar (v9)")
            .contains("newer build");

        assertThat(GuardianStatus.schemaState(0, 9))
            .contains("unstamped")
            .contains("expects v9");
    }

    @Test
    void durabilityWarningsAppearOnlyWhenTheyAreTrue() {
        String healthy = GuardianStatus.durabilityLine(0, 0, 0, 0, 0, false, true);
        assertThat(healthy)
            .isEqualTo("sinkDrops=0 quarantined=0 recovered=0 qOverflow=0 qWriteFail=0")
            .doesNotContain("RETENTION")
            .doesNotContain("disabled")
            .doesNotContain("not durably retained");

        String retention = GuardianStatus.durabilityLine(0, 4, 1, 0, 0, true, true);
        assertThat(retention).contains("RETENTION LIMIT REACHED");

        String disabled = GuardianStatus.durabilityLine(0, 0, 0, 0, 0, false, false);
        assertThat(disabled).contains("durable quarantine disabled");

        String writeFail = GuardianStatus.durabilityLine(0, 0, 0, 0, 3, false, true);
        assertThat(writeFail).contains("quarantine write failures are not durably retained");
    }

    @Test
    void durabilityRowKeepsEverySinkCounter() {
        // Regression guard: the old report printed one line per counter. If a
        // counter is dropped during compression the operator loses the number
        // entirely, which is worse than a long report.
        String row = GuardianStatus.durabilityLine(1, 2, 3, 4, 5, false, true);
        assertThat(row)
            .contains("sinkDrops=1")
            .contains("quarantined=2")
            .contains("recovered=3")
            .contains("qOverflow=4")
            .contains("qWriteFail=5");
    }

    @Test
    void paddingNeverTruncates() {
        assertThat(GuardianStatus.padRight("ab", 5)).isEqualTo("ab   ");
        assertThat(GuardianStatus.padRight("abcdef", 5)).isEqualTo("abcdef");
        assertThat(GuardianStatus.padRight("", 3)).isEqualTo("   ");
        assertThat(GuardianStatus.padRight(null, 3)).isEmpty();
    }

    @Test
    void aLabelOfExactlyTheLabelWidthStillLeavesASeparator() {
        // padRight adds nothing here, so without the mandatory space the value
        // would butt straight against the label and the column would shift.
        String label = "x".repeat(GuardianStatus.LABEL_WIDTH);
        String rendered = renderOne(label, "VALUE");
        assertThat(rendered)
            .as("a full-width label must still be separated from its value: [%s]", rendered)
            .endsWith(" VALUE");
        assertThat(renderOne(label, "VALUE").indexOf("VALUE"))
            .isEqualTo(renderOne("ab", "VALUE").indexOf("VALUE"));
    }

    @Test
    void anOverlongLabelDoesNotSwallowTheValue() {
        String label = "y".repeat(GuardianStatus.LABEL_WIDTH + 6);
        assertThat(renderOne(label, "VALUE"))
            .as("an over-long label must not glue the value onto the label")
            .endsWith(" VALUE")
            .contains("y VALUE");
    }

    /**
     * Render one row through the same helper the report uses. Reflection is
     * avoided by calling the package-private row builder via a probe list.
     */
    private static String renderOne(String label, String value) {
        List<String> out = new java.util.ArrayList<>(1);
        GuardianStatus.row(out, label, value);
        return out.get(0);
    }
}
