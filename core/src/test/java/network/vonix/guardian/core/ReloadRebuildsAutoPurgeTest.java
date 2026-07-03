/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.purge.AutoPurgeScheduler;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.2 Y3 (P2-4 close-out): {@code /vg reload} must reschedule the running
 * {@link AutoPurgeScheduler} when {@code purge.autoPurgeTime} or
 * {@code purge.autoPurgeSeconds} changed on disk.
 *
 * <p>Pre-Y3 the merged config entered the new value but the daemon kept its
 * original schedule until server restart — an operator who edited
 * {@code autoPurgeTime} would silently keep hitting the old window.
 */
class ReloadRebuildsAutoPurgeTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-y3-purge-reload-test");
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

    private static GuardianConfig cfg(Path dbDir, long autoPurgeSeconds, String autoPurgeTime) {
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
            new GuardianConfig.Storage(false),
            new GuardianConfig.Rollback(16),
            "aqua",
            "en_us"
        );
    }

    @Test
    @DisplayName("Y3 P2-4: changing purge.autoPurgeTime hot-swaps the running scheduler")
    void reloadUpdatesAutoPurgeTime(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // 30 days = 2_592_000 is the validation floor; use that + 60 days.
        GuardianConfig initial = cfg(tmp, /*retention*/2_592_000L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            AutoPurgeScheduler sched = g.autoPurgeScheduler();
            assertThat(sched).as("boot creates scheduler").isNotNull();
            assertThat(sched.runTime()).isEqualTo(LocalTime.of(3, 30));
            assertThat(sched.retentionSeconds()).isEqualTo(2_592_000L);
            assertThat(sched.isEnabled()).isTrue();

            // Change the time and retention on disk.
            GuardianConfig bumped = cfg(tmp, 5_184_000L, "22:15");
            ConfigLoader.save(cfgPath, bumped);
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(r.hotSwapped()).contains("purge");
            // The SAME scheduler instance must have updated its schedule.
            assertThat(g.autoPurgeScheduler()).as("Y3: no instance swap needed").isSameAs(sched);
            assertThat(sched.runTime()).isEqualTo(LocalTime.of(22, 15));
            assertThat(sched.retentionSeconds()).isEqualTo(5_184_000L);
            assertThat(sched.isEnabled()).isTrue();
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("Y3 P2-4: setting autoPurgeSeconds=0 disables the running scheduler on reload")
    void reloadCanDisableAutoPurge(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = cfg(tmp, 2_592_000L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            AutoPurgeScheduler sched = g.autoPurgeScheduler();
            assertThat(sched.isEnabled()).isTrue();

            GuardianConfig disabled = cfg(tmp, 0L, "03:30");
            ConfigLoader.save(cfgPath, disabled);
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(r.hotSwapped()).contains("purge");
            assertThat(sched.isEnabled()).isFalse();
            assertThat(sched.retentionSeconds()).isEqualTo(0L);
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("Y3 P2-4: no-op purge reload does not spam applyConfig work")
    void reloadNoopWhenPurgeUnchanged(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = cfg(tmp, 2_592_000L, "03:30");
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            AutoPurgeScheduler sched = g.autoPurgeScheduler();
            LocalTime beforeTime = sched.runTime();

            Guardian.ReloadResult r = g.reloadConfig(cfgPath);

            assertThat(r.errors()).isEmpty();
            assertThat(r.hotSwapped()).doesNotContain("purge");
            assertThat(sched.runTime()).isEqualTo(beforeTime);
        } finally {
            g.close();
        }
    }
}
