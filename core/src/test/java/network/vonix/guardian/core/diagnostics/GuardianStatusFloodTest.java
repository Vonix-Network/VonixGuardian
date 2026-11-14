package network.vonix.guardian.core.diagnostics;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code /vg status} flood: 62 lines of chat on a healthy server, with
 * {@code [VonixGuardian]} on every line, pushed the one line an operator needed
 * off the screen.
 *
 * <p>These tests boot a real {@link Guardian} against a temp SQLite file and
 * assert on the rendered report, so the budget is measured against a real boot
 * rather than a hand-built string. They deliberately do not pin exact wording
 * beyond the labels; what is pinned is the line budget and the presence of every
 * counter the compression was allowed to move but not to lose.
 */
class GuardianStatusFloodTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-status-flood-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) { return true; }
        @Override public boolean tryClearBlock(String w, int x, int y, int z) { return true; }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String i, int a, String m) { return true; }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String i, int a) { return true; }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String e, String m) { return true; }
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;

    /** Hard ceiling for a healthy server's report. */
    private static final int HEALTHY_LINE_BUDGET = 40;

    @Test
    void healthyReportFitsOnOneChatScreen(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            List<String> report = GuardianStatus.render(g);
            assertThat(report.size())
                .as("a healthy status report must fit on a screen; was 62 lines before.%n%s",
                    String.join("\n", report))
                .isLessThanOrEqualTo(HEALTHY_LINE_BUDGET);
            assertThat(report).isNotEmpty();
        } finally {
            g.close();
        }
    }

    @Test
    void sectionHeadersAreFewAndCarryNoBrandAtAll(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            List<String> report = GuardianStatus.render(g);
            List<String> headers = new ArrayList<>();
            for (String line : report) {
                if (line.startsWith("§ ")) {
                    headers.add(line);
                }
            }
            assertThat(headers)
                .as("five sections: identity, storage, writer, recovery, configuration")
                .hasSize(5);
            // The cell brands the first section once. If the core renderer put the
            // name on every header, the cell's branding would be redundant and the
            // 62-line flood would come back through the header path.
            assertThat(String.join("\n", report))
                .as("the renderer must not repeat the product name on every section")
                .doesNotContain("[VonixGuardian] § ");
            int branded = 0;
            for (String line : report) {
                if (line.contains("[VonixGuardian]")) {
                    branded++;
                }
            }
            assertThat(branded)
                .as("core must not brand any line; the cell brands the first section")
                .isZero();
        } finally {
            g.close();
        }
    }

    @Test
    void compressionKeepsEverySinkCounter(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            String report = String.join("\n", GuardianStatus.render(g));
            // Every counter the pre-compression report printed one-per-line. Losing
            // any of these would be a worse defect than the long report was.
            assertThat(report)
                .contains("submitted=")
                .contains("gated=")
                .contains("dropped=")
                .contains("sinkDrops=")
                .contains("quarantined=")
                .contains("recovered=")
                .contains("qOverflow=")
                .contains("qWriteFail=")
                .contains("depth")
                .contains("suppressed=")
                .contains("passed=")
                .contains("evictions=")
                .contains("capDrops=")
                .contains("per-node overrides")
                .contains("alloc ");
        } finally {
            g.close();
        }
    }

    @Test
    void everyMixinSourceTypeIsStillNamedSomewhere(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            String report = String.join("\n", GuardianStatus.render(g));
            for (String type : List.of("BURN", "SPREAD", "IGNITE", "FADE", "FORM",
                    "LEAVES_DECAY", "DISPENSE", "ENTITY_CHANGE_BLOCK")) {
                assertThat(report)
                    .as("%s must remain discoverable; a type that vanishes from the report "
                        + "looks like a type that does not exist", type)
                    .contains(type);
            }
        } finally {
            g.close();
        }
    }

    @Test
    void healthRowDoesNotClaimToBeHealth(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            String report = String.join("\n", GuardianStatus.render(g));
            // SELECT 1 answers "can I reach it", not "is it healthy". A locked
            // table, a full disk, and a missing grant all pass SELECT 1.
            assertThat(report)
                .as("the reachability row must not be named health at all")
                .doesNotContain("health");
            assertThat(report)
                .contains("reachable")
                .contains("SELECT 1");
            assertThat(report)
                .as("the schema row must name the stamped version, not the jar's constant")
                .contains("schema")
                .contains("v" + network.vonix.guardian.core.storage.Schema.CURRENT_VERSION);
        } finally {
            g.close();
        }
    }

    @Test
    void reportNamesNoNullValue(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            List<String> report = GuardianStatus.render(g);
            for (String line : report) {
                assertThat(line.trim())
                    .as("a row must never end in the word null: [%s]", line)
                    .doesNotEndWith(" null");
                assertThat(line)
                    .as("a null value must render as %s: [%s]", GuardianStatus.NOT_SET, line)
                    .doesNotContain("=null");
            }
        } finally {
            g.close();
        }
    }

    @Test
    void openBatchAndRepairRowsAreNamedInline(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            long batchId = g.dao().openRollbackBatch(null, 1, "{}", List.of(1L, 2L));
            g.dao().markRepairRequired(List.of(
                new network.vonix.guardian.core.storage.GuardianDao.RepairRequired(
                    9L, null, batchId, "clear failed", 1L)));

            String report = String.join("\n", GuardianStatus.render(g));
            assertThat(report)
                .contains("batch " + batchId)
                .contains("mode=RESTORE")
                .contains("affected=2")
                .contains("repair action 9")
                .contains("clear failed")
                .contains("/vg batch close");
        } finally {
            g.close();
        }
    }

    @Test
    void cleanServerSaysNoneRatherThanZeroForRecovery(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(defaults(tmp), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            String report = String.join("\n", GuardianStatus.render(g));
            assertThat(report)
                .contains("open batches")
                .contains("none")
                .contains("repair required");
            // "0" reads as a count an operator should watch; "none" reads as
            // nothing to do.
            assertThat(report).doesNotContain("open batches 0");
        } finally {
            g.close();
        }
    }

    private static GuardianConfig defaults(Path tmp) {
        var d = GuardianConfig.defaults();
        return new GuardianConfig(
            new GuardianConfig.Database("sqlite", tmp.resolve("status-flood.db").toString(),
                null, null, null, null, GuardianConfig.Hikari.defaults()),
            d.queue(),
            d.logFile(),
            d.actions(),
            d.permissions(),
            d.lookup(),
            d.privacy(),
            d.purge(),
            d.storage(),
            d.rollback(),
            d.theme(),
            d.language()
        );
    }
}
