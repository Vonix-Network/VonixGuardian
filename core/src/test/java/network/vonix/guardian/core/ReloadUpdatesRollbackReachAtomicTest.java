/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.rollback.RollbackEngine;
import network.vonix.guardian.core.rollback.WorldMutator;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.2 Y3 (P2-1 close-out): the {@code explosionSupplementalReach} field on
 * {@link RollbackEngine} is now {@code volatile} and mutated by a setter that
 * mirrors what {@code Guardian.reloadConfig} does on a {@code /vg reload}.
 *
 * <p>This test verifies memory-atomicity: while one thread hammers
 * {@link RollbackEngine#setExplosionSupplementalReach(int)} (as if operator
 * were bouncing {@code /vg reload} in rapid succession) another thread reads
 * {@link RollbackEngine#getExplosionSupplementalReach()} — every observed value
 * must be one of the two writer values. No torn read, no phantom value.
 *
 * <p>We don't drive real {@code rollback()} through this — the DAO would need
 * a full test fixture. The property being verified is that the {@code volatile}
 * publication contract on the field holds under contention, which is what a
 * concurrent {@code /vg rollback} + {@code /vg reload} race relies on.
 */
class ReloadUpdatesRollbackReachAtomicTest {

    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public void setBlock(String w, int x, int y, int z, String id, String meta) {}
        @Override public void giveOrDrop(String w, int x, int y, int z, String id, int a, String meta) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String id, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String meta) {}
    };

    @Test
    @DisplayName("Y3 P2-1: setExplosionSupplementalReach is atomically published to readers")
    void concurrentSetAndReadNeverObservesTornValue() throws Exception {
        // A stub DAO — the fields we care about don't exercise it.
        GuardianDao stubDao = (GuardianDao) java.lang.reflect.Proxy.newProxyInstance(
            GuardianDao.class.getClassLoader(),
            new Class<?>[]{GuardianDao.class},
            (proxy, method, args) -> {
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class) return false;
                if (ret == int.class) return 0;
                if (ret == long.class) return 0L;
                return null;
            });

        RollbackEngine engine = new RollbackEngine(
            stubDao, NOOP_MUTATOR, Runnable::run, /*reach*/16);
        assertThat(engine.getExplosionSupplementalReach()).isEqualTo(16);

        int a = 32;
        int b = 96;
        int iterations = 100_000;
        AtomicInteger bogusReads = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Writer: bounce between two known values.
            pool.submit(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < iterations && !stop.get(); i++) {
                    engine.setExplosionSupplementalReach((i & 1) == 0 ? a : b);
                }
            });
            // Reader: observe values, count anything not in {a, b, initial 16}.
            pool.submit(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < iterations && !stop.get(); i++) {
                    int v = engine.getExplosionSupplementalReach();
                    if (v != a && v != b && v != 16) {
                        bogusReads.incrementAndGet();
                    }
                }
            });
            pool.shutdown();
            boolean finished = pool.awaitTermination(20, TimeUnit.SECONDS);
            stop.set(true);
            assertThat(finished).as("workers must finish inside 20s").isTrue();
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        assertThat(bogusReads.get())
            .as("volatile publication must expose only writer-set values — no torn reads")
            .isZero();
        // Final value must be one of the two writer values.
        int finalReach = engine.getExplosionSupplementalReach();
        assertThat(finalReach).isIn(a, b);
    }

    @Test
    @DisplayName("Y3 P2-1: setExplosionSupplementalReach rejects negative values")
    void setterRejectsNegative() {
        GuardianDao stubDao = (GuardianDao) java.lang.reflect.Proxy.newProxyInstance(
            GuardianDao.class.getClassLoader(),
            new Class<?>[]{GuardianDao.class},
            (proxy, method, args) -> null);
        RollbackEngine engine = new RollbackEngine(stubDao, NOOP_MUTATOR, Runnable::run, 16);

        try {
            engine.setExplosionSupplementalReach(-1);
            org.junit.jupiter.api.Assertions.fail("negative reach must throw");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains(">= 0");
        }
        assertThat(engine.getExplosionSupplementalReach())
            .as("failed set must not mutate the field")
            .isEqualTo(16);
    }
}
