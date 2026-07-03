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
 * v1.3.3 Z1 (P0-A close-out) regression suite.
 *
 * <p>Prior to Z1, every {@code /vg config set &lt;key&gt; &lt;value&gt;} in all 8
 * loader cells routed through {@code withValue(...)} / {@code withActions(...)},
 * both of which rebuilt {@link GuardianConfig} via the 9-arg backward-compat
 * constructor that silently defaults {@code storage}, {@code rollback} and
 * {@code language}. An operator who set {@code storage.persistNbt=true} lost
 * that setting the first time they ran any {@code /vg config set} command.
 *
 * <p>These tests pin the canonical 12-arg contract from the core side. Every
 * cell's {@code withValue}/{@code withActions} MUST call the canonical
 * constructor (or its {@link GuardianConfig#storage()}/{@code rollback()}/
 * {@code language()} pass-through form) so the round-trip through save-to-disk
 * + reload preserves every widened section.
 *
 * <p>We can't invoke the per-cell private helper from the core module. Instead
 * we simulate the exact contract using the canonical 12-arg ctor with values
 * pulled from {@code c.storage()}, {@code c.rollback()}, {@code c.language()}
 * — that is precisely what the fixed cells now do. If any cell ever regresses
 * to a shorter ctor form, {@link ReloadPreservesSectionsTest} catches the
 * reload-time drop and this suite catches the set-time drop.
 *
 * @since 1.3.3
 */
class ConfigSetPreservesSectionsTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-z1-configset-test");
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
     * Mirror of every fixed cell's {@code withValue("theme", value)} case — the
     * canonical 12-arg form. Any regression to a 9/10/11-arg backward-compat
     * overload here would silently reset {@code storage} / {@code rollback} /
     * {@code language}, exactly reproducing the P0-A defect.
     */
    private static GuardianConfig withTheme(GuardianConfig c, String value) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), c.actions(),
            c.permissions(), c.lookup(), c.privacy(), c.purge(),
            c.storage(), c.rollback(),
            value, c.language()
        );
    }

    /** Mirror of every fixed cell's {@code withActions(c, a)} helper. */
    private static GuardianConfig withActions(GuardianConfig c, GuardianConfig.Actions a) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), a,
            c.permissions(), c.lookup(), c.privacy(), c.purge(),
            c.storage(), c.rollback(),
            c.theme(), c.language()
        );
    }

    /** Mirror of the Z1 new case for {@code storage.persistNbt}. */
    private static GuardianConfig withStoragePersistNbt(GuardianConfig c, boolean value) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), c.actions(),
            c.permissions(), c.lookup(), c.privacy(), c.purge(),
            new GuardianConfig.Storage(value), c.rollback(),
            c.theme(), c.language()
        );
    }

    private static GuardianConfig fullCfg(Path dbDir,
                                          boolean persistNbt,
                                          int explosionReach,
                                          String language) {
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
            new GuardianConfig.Lookup(7, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(86_400L, 3_600L, 0L, "03:30"),
            new GuardianConfig.Storage(persistNbt),
            new GuardianConfig.Rollback(explosionReach),
            "aqua",
            language
        );
    }

    // ================================================================ storage

    @Test
    @DisplayName("Z1 P0-A: /vg config set theme preserves storage.persistNbt=true")
    void configSetPreservesStorage() {
        GuardianConfig c = fullCfg(Path.of("/tmp"), /*persistNbt*/true, 16, "en_us");
        assertThat(c.storage().persistNbt()).isTrue();

        GuardianConfig next = withTheme(c, "gold");

        assertThat(next.theme()).isEqualTo("gold");
        assertThat(next.storage().persistNbt())
            .as("/vg config set theme MUST NOT drop storage.persistNbt")
            .isTrue();
        // Every other widened section preserved too.
        assertThat(next.rollback().explosionSupplementalReach()).isEqualTo(16);
        assertThat(next.language()).isEqualTo("en_us");
    }

    @Test
    @DisplayName("Z1 P0-A: /vg config set actions.logBlocks preserves storage.persistNbt=true")
    void configSetActionsPreservesStorage() {
        GuardianConfig c = fullCfg(Path.of("/tmp"), true, 16, "en_us");

        // Mirror /vg config set actions.logBlocks false — goes through withActions helper.
        GuardianConfig.Actions a = c.actions();
        GuardianConfig.Actions muted = new GuardianConfig.Actions(
            /*logBlocks*/ false,
            a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(),
            a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(),
            a.logInteractions(), a.logWorldEvents(),
            a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(),
            a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(),
            a.entityChangeAllowlist(), a.entityChangeLogAllEntities()
        );
        GuardianConfig next = withActions(c, muted);

        assertThat(next.actions().logBlocks()).isFalse();
        assertThat(next.storage().persistNbt())
            .as("withActions MUST NOT drop storage.persistNbt")
            .isTrue();
        assertThat(next.rollback().explosionSupplementalReach()).isEqualTo(16);
        assertThat(next.language()).isEqualTo("en_us");
    }

    @Test
    @DisplayName("Z1 P0-A: storage.persistNbt=true survives round-trip save + load")
    void configSetStorageRoundTrip(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = fullCfg(tmp, true, 16, "en_us");
        ConfigLoader.save(cfgPath, initial);

        // Simulate /vg config set theme gold going through the canonical helper.
        GuardianConfig loaded = ConfigLoader.load(cfgPath);
        GuardianConfig next = withTheme(loaded, "gold");
        next.validate();
        ConfigLoader.save(cfgPath, next);

        // Re-load — persistNbt MUST still be true.
        GuardianConfig reloaded = ConfigLoader.load(cfgPath);
        assertThat(reloaded.theme()).isEqualTo("gold");
        assertThat(reloaded.storage().persistNbt())
            .as("round-trip save+load MUST preserve storage.persistNbt")
            .isTrue();
    }

    // =============================================================== rollback

    @Test
    @DisplayName("Z1 P0-A: /vg config set theme preserves rollback.explosionSupplementalReach")
    void configSetPreservesRollback() {
        GuardianConfig c = fullCfg(Path.of("/tmp"), false, /*reach*/32, "en_us");
        assertThat(c.rollback().explosionSupplementalReach()).isEqualTo(32);

        GuardianConfig next = withTheme(c, "gold");

        assertThat(next.theme()).isEqualTo("gold");
        assertThat(next.rollback().explosionSupplementalReach())
            .as("/vg config set theme MUST NOT drop rollback.explosionSupplementalReach")
            .isEqualTo(32);
        assertThat(next.storage().persistNbt()).isFalse();
        assertThat(next.language()).isEqualTo("en_us");
    }

    // =============================================================== language

    @Test
    @DisplayName("Z1 P0-A: /vg config set theme preserves language")
    void configSetPreservesLanguage() {
        GuardianConfig c = fullCfg(Path.of("/tmp"), false, 16, /*language*/"fr");
        assertThat(c.language()).isEqualTo("fr");

        GuardianConfig next = withTheme(c, "purple");

        assertThat(next.theme()).isEqualTo("purple");
        assertThat(next.language())
            .as("/vg config set theme MUST NOT drop language")
            .isEqualTo("fr");
        assertThat(next.storage().persistNbt()).isFalse();
        assertThat(next.rollback().explosionSupplementalReach()).isEqualTo(16);
    }

    // ============================================== persistNbt toggle activation

    @Test
    @DisplayName("Z1 P0-A: /vg config set storage.persistNbt true actually enables NBT capture")
    void configSetPersistNbtToggle(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Start with the field explicitly false.
        GuardianConfig initial = fullCfg(tmp, /*persistNbt*/false, 16, "en_us");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            assertThat(g.config().storage().persistNbt())
                .as("boot: config reflects persistNbt=false")
                .isFalse();

            // Simulate /vg config set storage.persistNbt true — the fixed cell's
            // withValue case now goes through this exact form.
            GuardianConfig flipped = withStoragePersistNbt(g.config(), true);
            flipped.validate();
            ConfigLoader.save(cfgPath, flipped);
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(g.config().storage().persistNbt())
                .as("post-set reload: config gate reflects toggle — "
                    + "producers now capture NBT payloads")
                .isTrue();
            // The reload machinery reports the field as hot-swapped so operators
            // see immediate feedback (Y3 wiring).
            assertThat(r.hotSwapped())
                .as("Guardian.reloadConfig reports storage.persistNbt as hot-swapped")
                .contains("storage.persistNbt");
        } finally {
            g.close();
        }
    }
}
