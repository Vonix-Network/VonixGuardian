/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.EventGate;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.3 Z4 (G-Y3-1 P2 close-out): {@link Guardian#reloadConfig(Path)} must
 * publish {@code this.gate} BEFORE {@code this.config}.
 *
 * <p>Pre-Z4 the swap was config-first-then-gate. A submitter that raced the
 * reload could observe the NEW {@link GuardianConfig} (fresh persistNbt /
 * language / rollback flags) while still routing through the OLD
 * {@link EventGate} — a brief window in which auxiliary config values are
 * live but the terminal hook chain (blacklist / per-world / PreLogEvent) is
 * still the old one. In practice this let e.g. a newly-toggled persistNbt
 * flag capture NBT under old-blacklist semantics.
 *
 * <h3>Detection strategy</h3>
 *
 * <p>Under the correct swap order (gate first, then config) a concurrent
 * observer sampling {@code (guardian.gate(), guardian.config())} pairs sees a
 * NEW gate REFERENCE appear BEFORE the corresponding NEW config REFERENCE for
 * that reload iteration. If we assign each newly-observed gate/config
 * reference a monotonically-increasing "observation epoch" (per side) in the
 * order they first appear, the invariant becomes:
 *
 * <pre>
 *   for every observed pair (g, c):
 *       gateSeenEpoch(g) &gt;= configSeenEpoch(c)
 * </pre>
 *
 * <p>Under the pre-Z4 bug (config first) the observer sees a NEW config
 * reference BEFORE the corresponding NEW gate reference — the new config
 * gets a strictly-greater configSeenEpoch than the still-old gate's
 * gateSeenEpoch, violating the invariant.
 *
 * <p>This approach avoids any external epoch-stamping race: epochs are
 * assigned by the observer thread itself in observation order.
 */
class ReloadSwapOrderTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-z4-swap-order-test");
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

    /** Base config: theme + language + rollback reach are cycled every reload. */
    private static GuardianConfig cfg(Path dbDir, String theme, String language, int reach) {
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
            new GuardianConfig.Storage(false),
            new GuardianConfig.Rollback(reach),
            theme,
            language
        );
    }

    @Test
    @DisplayName("Z4 G-Y3-1: concurrent submit never observes new config with stale gate")
    void concurrentReloadNeverExposesConfigBeforeGate(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        GuardianConfig initial = cfg(tmp, "aqua", "en_us", 16);
        ConfigLoader.save(cfgPath, initial);

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);

            List<EventGate> observedGates = new ArrayList<>(200_000);
            List<GuardianConfig> observedCfgs = new ArrayList<>(200_000);

            AtomicBoolean stop = new AtomicBoolean(false);
            CountDownLatch ready = new CountDownLatch(2);
            int reloads = 60;

            Thread observer = new Thread(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                while (!stop.get()) {
                    // Snapshot pair. The pair is captured non-atomically on
                    // purpose: if the fix is regressed, a stale gate will
                    // be paired with a fresh config here. Read config()
                    // FIRST so that any (config-first) publication regressions
                    // produce clearly-inverted pairs.
                    GuardianConfig cf = g.config();
                    EventGate gt = g.gate();
                    observedGates.add(gt);
                    observedCfgs.add(cf);
                }
            }, "vg-z4-observer");
            observer.setDaemon(true);
            observer.start();

            Thread reloader = new Thread(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                try {
                    for (int i = 1; i <= reloads; i++) {
                        // Cycle rollback reach on every reload — that field
                        // is the fastest producer of a fresh GuardianConfig
                        // record (each canonical constructor call yields a
                        // new identity). theme cycled too for hot-swap
                        // observability.
                        String theme = (i % 2 == 0) ? "aqua" : "gold";
                        String lang  = (i % 2 == 0) ? "en_us" : "fr";
                        int    reach = 16 + (i % 8);
                        ConfigLoader.save(cfgPath, cfg(tmp, theme, lang, reach));
                        g.reloadConfig(cfgPath);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "vg-z4-reloader");
            reloader.setDaemon(true);
            reloader.start();

            reloader.join(60_000);
            stop.set(true);
            observer.join(5_000);

            assertThat(reloader.isAlive()).as("reloader finished").isFalse();
            assertThat(observer.isAlive()).as("observer finished").isFalse();
            assertThat(observedGates)
                .as("observer must accumulate a non-trivial sample")
                .hasSizeGreaterThan(200);

            // Assign monotonically-increasing observer-local epochs to each
            // NEW gate/config REFERENCE the observer first sees. Then verify
            // gateSeenEpoch >= configSeenEpoch for every recorded pair.
            IdentityHashMap<EventGate, Integer> gateEpoch = new IdentityHashMap<>();
            IdentityHashMap<GuardianConfig, Integer> cfgEpoch = new IdentityHashMap<>();
            int gEpoch = 0, cEpoch = 0;
            int violations = 0;
            int violationExample = -1;
            for (int i = 0; i < observedGates.size(); i++) {
                EventGate g0 = observedGates.get(i);
                GuardianConfig c0 = observedCfgs.get(i);
                Integer ge = gateEpoch.get(g0);
                if (ge == null) {
                    ge = gEpoch++;
                    gateEpoch.put(g0, ge);
                }
                Integer ce = cfgEpoch.get(c0);
                if (ce == null) {
                    ce = cEpoch++;
                    cfgEpoch.put(c0, ce);
                }
                if (ge < ce) {
                    violations++;
                    if (violationExample < 0) violationExample = i;
                }
            }

            assertThat(gEpoch)
                .as("multiple distinct gate references must have been observed")
                .isGreaterThan(1);
            assertThat(cEpoch)
                .as("multiple distinct config references must have been observed")
                .isGreaterThan(1);
            assertThat(violations)
                .as("Z4 invariant: no submitter sees (staleGate, freshConfig). "
                    + "First violation index=" + violationExample
                    + " out of " + observedGates.size() + " samples "
                    + "(gates=" + gEpoch + ", configs=" + cEpoch + ")")
                .isZero();
        } finally {
            g.close();
        }
    }

    @Test
    @DisplayName("Z4 G-Y3-1: single-threaded reload leaves gate+config internally consistent")
    void singleReloadKeepsGateAndConfigCoherent(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        ConfigLoader.save(cfgPath, cfg(tmp, "aqua", "en_us", 16));

        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            g.setConfigPath(cfgPath);
            EventGate bootGate = g.gate();
            GuardianConfig bootCfg = g.config();
            assertThat(bootGate).isNotNull();
            assertThat(bootCfg.theme()).isEqualTo("aqua");

            // Rewrite and reload.
            ConfigLoader.save(cfgPath, cfg(tmp, "gold", "fr", 24));
            Guardian.ReloadResult r = g.reloadConfig(cfgPath);
            assertThat(r.errors()).as("reload should not report errors").isEmpty();

            EventGate afterGate = g.gate();
            GuardianConfig afterCfg = g.config();
            assertThat(afterGate).as("reload rebuilds gate").isNotSameAs(bootGate);
            assertThat(afterCfg).as("reload rebuilds config").isNotSameAs(bootCfg);
            assertThat(afterCfg.theme()).isEqualTo("gold");
            assertThat(afterCfg.language()).isEqualTo("fr");
            assertThat(afterCfg.rollback().explosionSupplementalReach()).isEqualTo(24);
        } finally {
            g.close();
        }
    }
}
