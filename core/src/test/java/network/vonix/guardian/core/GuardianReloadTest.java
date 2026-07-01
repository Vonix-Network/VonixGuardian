/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3-B1 regression: /vg reload must hot-swap the safe subset and preserve
 * old values for restart-required fields. Mirrors CoreProtect's /co reload
 * contract as documented in docs/COREPROTECT-COMPARISON.md § 1.2.
 */
class GuardianReloadTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-reload-test");
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

    /** Build a minimal config that (a) uses a fresh temp SQLite file, (b) disables the log-file. */
    private static GuardianConfig minimalCfg(Path dbDir, String theme, int defaultPage, boolean hashIps, long consoleFloor) {
        return new GuardianConfig(
            new GuardianConfig.Database("sqlite", dbDir.resolve("test.db").toString(), null, null, null),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                500L, 8192,
                List.of(), false
            ),
            new GuardianConfig.Permissions(true, 3),
            new GuardianConfig.Lookup(defaultPage, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(hashIps, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(consoleFloor, 3_600L, 0L, "03:30"),
            theme
        );
    }

    @Test
    @DisplayName("hot-swap: theme + purge floors + defaultPageSize + actions.logBlocks flip in memory")
    void hotSwapAppliesInMemory(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = minimalCfg(tmp, "aqua", 7, false, 86_400L);
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            assertThat(g.config().theme()).isEqualTo("aqua");
            assertThat(g.config().lookup().defaultPageSize()).isEqualTo(7);
            assertThat(g.config().purge().minAgeSecondsConsole()).isEqualTo(86_400L);
            assertThat(g.config().actions().logBlocks()).isTrue();

            // Mutate hot-swap fields on disk.
            GuardianConfig mutated = new GuardianConfig(
                initial.database(),
                initial.queue(),
                initial.logFile(),
                new GuardianConfig.Actions(
                    false, // logBlocks flipped off
                    initial.actions().logContainers(), initial.actions().logItems(),
                    initial.actions().logEntities(), initial.actions().logExplosions(),
                    initial.actions().logChat(), initial.actions().logCommands(),
                    initial.actions().logSessions(), initial.actions().logSigns(),
                    initial.actions().logInteractions(), initial.actions().logWorldEvents(),
                    initial.actions().worldBlacklist(), initial.actions().blockBlacklist(),
                    initial.actions().sourceBlacklist(),
                    initial.actions().entityBlockChangeCoalesceWindowMs(),
                    initial.actions().entityBlockChangeMaxTracked(),
                    initial.actions().entityChangeAllowlist(),
                    initial.actions().entityChangeLogAllEntities()
                ),
                initial.permissions(),
                new GuardianConfig.Lookup(15, 10_000, 200_000, 4),
                initial.privacy(),
                new GuardianConfig.Purge(120L, 7200L, 0L, "03:30"),
                "gold"
            );
            ConfigLoader.save(cfgPath, mutated);

            Guardian.ReloadResult r = g.reloadConfig(null); // uses configPath()
            assertThat(r.errors()).isEmpty();
            assertThat(r.requiresRestart()).isEmpty();
            assertThat(r.hotSwapped())
                .contains("actions", "lookup.defaultPageSize/maxRadius/maxResultRows", "purge", "theme");

            // In-memory config reflects new values.
            assertThat(g.config().theme()).isEqualTo("gold");
            assertThat(g.config().lookup().defaultPageSize()).isEqualTo(15);
            assertThat(g.config().lookup().maxResultRows()).isEqualTo(200_000);
            assertThat(g.config().purge().minAgeSecondsConsole()).isEqualTo(120L);
            assertThat(g.config().actions().logBlocks()).isFalse();
            assertThat(g.theme()).isNotNull();
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("restart-required: queue.maxSize + permissions + lookup.maxConcurrent stay live-unchanged and report")
    void restartRequiredHeldBack(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = minimalCfg(tmp, "aqua", 7, false, 86_400L);
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            int liveMax = g.config().queue().maxSize();
            int liveConc = g.config().lookup().maxConcurrent();
            int liveOp = g.config().permissions().defaultOpLevel();

            // Mutate restart-required fields on disk.
            GuardianConfig mutated = new GuardianConfig(
                initial.database(),
                new GuardianConfig.Queue(99_999, initial.queue().flushIntervalMs(), initial.queue().batchSize()),
                initial.logFile(),
                initial.actions(),
                new GuardianConfig.Permissions(initial.permissions().useLuckPerms(), 4),
                new GuardianConfig.Lookup(
                    initial.lookup().defaultPageSize(), initial.lookup().maxRadius(),
                    initial.lookup().maxResultRows(), 32),
                initial.privacy(),
                initial.purge(),
                initial.theme()
            );
            ConfigLoader.save(cfgPath, mutated);

            Guardian.ReloadResult r = g.reloadConfig(cfgPath);
            assertThat(r.errors()).isEmpty();
            assertThat(r.requiresRestart())
                .contains("queue", "permissions", "lookup.maxConcurrent");

            // In-memory values UNCHANGED.
            assertThat(g.config().queue().maxSize()).isEqualTo(liveMax);
            assertThat(g.config().lookup().maxConcurrent()).isEqualTo(liveConc);
            assertThat(g.config().permissions().defaultOpLevel()).isEqualTo(liveOp);
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("no-op reload: identical file yields empty hot/restart/errors lists")
    void noopReload(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = minimalCfg(tmp, "aqua", 7, false, 86_400L);
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);
            assertThat(r.errors()).isEmpty();
            assertThat(r.hotSwapped()).isEmpty();
            assertThat(r.requiresRestart()).isEmpty();
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("errors surface: bad config on disk produces error entry, no live-config change")
    void badConfigIsError(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = minimalCfg(tmp, "aqua", 7, false, 86_400L);
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            String liveTheme = g.config().theme();
            // Corrupt the file.
            java.nio.file.Files.writeString(cfgPath, "{ this is not valid JSON, at all: ][ } ");

            Guardian.ReloadResult r = g.reloadConfig(cfgPath);
            assertThat(r.errors()).hasSize(1);
            assertThat(r.errors().get(0)).contains("load failed");
            // Live config unchanged.
            assertThat(g.config().theme()).isEqualTo(liveTheme);
        } finally {
            g.close();
        }
    }

    // Silence an unused-import warning if signatures shift.
    @SuppressWarnings("unused") private static UUID uu() { return UUID.randomUUID(); }
}
