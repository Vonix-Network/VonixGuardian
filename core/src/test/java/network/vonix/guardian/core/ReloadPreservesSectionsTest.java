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
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.2 Y3 (P0-1 close-out) regression suite.
 *
 * <p>Prior to Y3, {@code Guardian.reloadConfig} rebuilt its merged {@link
 * GuardianConfig} via the 9-arg backward-compat constructor, which silently
 * filled {@code storage}, {@code rollback}, and {@code language} with their
 * {@code defaults()} — every {@code /vg reload} therefore reverted operator
 * overrides on those three sections. These tests pin the canonical 12-arg
 * behaviour: an operator-set value on disk must survive reload.
 */
class ReloadPreservesSectionsTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-y3-reload-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public void setBlock(String w, int x, int y, int z, String id, String meta) {}
        @Override public void giveOrDrop(String w, int x, int y, int z, String id, int a, String meta) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String id, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String meta) {}
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;

    /**
     * Build a full 12-arg config using the canonical form so the on-disk JSON
     * carries every section verbatim.
     */
    private static GuardianConfig fullCfg(Path dbDir,
                                          boolean persistNbt,
                                          int explosionReach,
                                          String language,
                                          long autoPurgeSeconds,
                                          String autoPurgeTime) {
        return new GuardianConfig(
            new GuardianConfig.Database("sqlite", dbDir.resolve("test.db").toString(), null, null, null, null, GuardianConfig.Hikari.defaults()),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30, true),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                500L, 8192,
                List.of(), false
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
            new GuardianConfig.Purge(86_400L, 3_600L, autoPurgeSeconds, autoPurgeTime),
            new GuardianConfig.Storage(persistNbt),
            new GuardianConfig.Rollback(explosionReach),
            "aqua",
            language
        );
    }

    // -------------------------------------------------------------- storage --

    @Test
    @DisplayName("Y3 P0-1: storage.persistNbt=true survives /vg reload")
    void reloadPreservesStorage(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = fullCfg(tmp, /*persistNbt*/true, 16, "en_us", 0L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            assertThat(g.config().storage().persistNbt()).as("boot preserves storage.persistNbt").isTrue();

            // Reload from the same on-disk file — pre-Y3 this would revert persistNbt to false.
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(g.config().storage().persistNbt())
                .as("reload must NOT revert storage.persistNbt to defaults")
                .isTrue();
        } finally {
            g.close();
        }
    }

    // ------------------------------------------------------------- rollback --

    @Test
    @DisplayName("Y3 P0-1: rollback.explosionSupplementalReach survives /vg reload")
    void reloadPreservesRollbackReach(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = fullCfg(tmp, false, /*explosionReach*/64, "en_us", 0L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            // Boot wiring: the engine must have received the operator value, not MAX_TNT_REACH.
            assertThat(g.rollbackEngine().getExplosionSupplementalReach())
                .as("boot wires rollback.explosionSupplementalReach (Y3 P1-1)")
                .isEqualTo(64);
            assertThat(g.config().rollback().explosionSupplementalReach()).isEqualTo(64);

            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(g.config().rollback().explosionSupplementalReach())
                .as("reload must NOT revert rollback reach")
                .isEqualTo(64);
            assertThat(g.rollbackEngine().getExplosionSupplementalReach())
                .as("reload applies reach to the running engine")
                .isEqualTo(64);
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("Y3 P0-1: changing rollback.explosionSupplementalReach on disk is applied on reload")
    void reloadAppliesNewRollbackReach(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = fullCfg(tmp, false, /*explosionReach*/16, "en_us", 0L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            assertThat(g.rollbackEngine().getExplosionSupplementalReach()).isEqualTo(16);

            // Rewrite disk with a bumped reach and reload.
            GuardianConfig bumped = fullCfg(tmp, false, 128, "en_us", 0L, "03:30");
            ConfigLoader.save(cfgPath, bumped);
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(r.hotSwapped()).contains("rollback.explosionSupplementalReach");
            assertThat(g.config().rollback().explosionSupplementalReach()).isEqualTo(128);
            assertThat(g.rollbackEngine().getExplosionSupplementalReach())
                .as("engine reflects new reach immediately, no restart")
                .isEqualTo(128);
        } finally {
            g.close();
        }
    }

    // ------------------------------------------------------------- language --

    @Test
    @DisplayName("Y3 P0-1: non-en_us language survives /vg reload")
    void reloadPreservesLanguage(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = fullCfg(tmp, false, 16, /*language*/"de", 0L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            assertThat(g.config().language()).isEqualTo("de");

            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(g.config().language())
                .as("reload must NOT revert language to en_us")
                .isEqualTo("de");
        } finally {
            g.close();
        }
    }
}
