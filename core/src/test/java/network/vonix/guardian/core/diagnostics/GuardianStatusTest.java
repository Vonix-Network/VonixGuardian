package network.vonix.guardian.core.diagnostics;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

class GuardianStatusTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-status-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {}
        @Override public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {}
        @Override public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {}
        @Override public void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {}
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

            String report = String.join("\n", GuardianStatus.render(g));

            assertThat(report).contains("§ Coalescer");
            assertThat(report).contains("active");
            assertThat(report).contains("suppressed");
            assertThat(report).contains("capDrops");
        } finally {
            g.close();
        }
    }
}
