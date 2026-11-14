package network.vonix.guardian.core.diagnostics;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-level coverage of the {@code /vg status} report.
 *
 * <p>The report was reorganised from thirteen sections into five, and related
 * counters were folded onto shared rows, because the previous shape ran past a
 * screen of chat on a healthy server. The assertions here therefore name the
 * <em>row labels</em> rather than the old section headings: a section heading is
 * presentation, a row label is the promise that the number is still reported.
 */
class GuardianStatusTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-status-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {return true; }
        @Override public boolean tryClearBlock(String worldId, int x, int y, int z) {return true; }
        @Override public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {return true; }
        @Override public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {return true; }
        @Override public boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {return true; }
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;

    @Test
    void coalescerSectionReportsSuppressedEvictionsAndHardCapDrops(@TempDir Path tmp) throws Exception {
        GuardianConfig cfg = new GuardianConfig(
            new GuardianConfig.Database("sqlite", tmp.resolve("status.db").toString(), null, null, null, null, GuardianConfig.Hikari.defaults()),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30, true),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                60_000L, 4,
                List.of(), true
            ,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            false,
            true,
            true,
            false,
            true,
            false,
            true),
            new GuardianConfig.Permissions(true, 3, java.util.Map.of()),
            new GuardianConfig.Lookup(7, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(86_400L, 3_600L, 0L, "03:30"),
        GuardianConfig.Storage.defaults(),
        GuardianConfig.Rollback.defaults(),
            "aqua"
        ,
        "en_us");
        Guardian g = Guardian.boot(cfg, tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.submitEntityChangeBlock(null, "#mob:minecraft:zombie", "minecraft:overworld", 1, 64, 1,
                "minecraft:dirt", "minecraft:air", "test");
            g.submitEntityChangeBlock(null, "#mob:minecraft:zombie", "minecraft:overworld", 1, 64, 1,
                "minecraft:dirt", "minecraft:air", "test");
            for (int i = 0; i < 16; i++) {
                g.submitEntityChangeBlock(null, "#mob:minecraft:zombie", "minecraft:overworld", 100 + i, 64, 100 + i,
                    "minecraft:dirt", "minecraft:air", "test");
            }

            long batchId = g.dao().openRollbackBatch(null, 1, "{}", java.util.List.of(1L, 2L));
            g.dao().markRepairRequired(java.util.List.of(
                    new GuardianDao.RepairRequired(9L, null, batchId, "clear failed", 1L)));

            String report = String.join("\n", GuardianStatus.render(g));

            // Recovery detail: the operator must be able to see and name the batch.
            assertThat(report).contains("batch " + batchId);
            assertThat(report).contains("mode=RESTORE");
            assertThat(report).contains("affected=2");
            assertThat(report).contains("repair action 9");
            assertThat(report).contains("inspect with /vg lookup before re-running");

            // Auto-purge at 0 seconds is unbounded; that must be stated.
            assertThat(report).contains("0 = unbounded");

            // Boot does not replay open batches. This is the behaviour that makes
            // the "open batches" row a warning rather than housekeeping.
            g.rollbackEngine().recoverIncompleteBatches();
            assertThat(g.dao().findOpenRollbackBatches(20))
                    .extracting(GuardianDao.OpenRollbackBatch::id)
                    .contains(batchId);

            // Leading sentence, then every subsystem that used to have its own
            // section is still represented by a row.
            assertThat(report).contains("Status:").contains("storage").contains("waiting");
            assertThat(report).contains("coalescer");
            assertThat(report).contains("sinkDrops");
            assertThat(report).contains("quarantined", "recovered", "qOverflow", "qWriteFail");
            assertThat(report).contains("maintenance");
            assertThat(report).contains("suppressed=");
            assertThat(report).contains("evictions=");
            assertThat(report).contains("capDrops=");
            // The coalescer is configured with a 60s window and 4 tracked entries in
            // this fixture, so its row must not claim to be off.
            assertThat(report).contains("window 60000ms");
            assertThat(report).contains("/4");
        } finally {
            g.close();
        }
    }
}
