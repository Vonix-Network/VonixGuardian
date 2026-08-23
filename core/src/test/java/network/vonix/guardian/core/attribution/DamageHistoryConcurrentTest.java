/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class DamageHistoryConcurrentTest {

    @Test
    void concurrentRecordLookupForgetDoesNotThrow() throws Exception {
        DamageHistory dh = new DamageHistory(60_000L, 64);

        final int threads = 8;
        final int perThreadOps = 3_000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> firstErr = new AtomicReference<>();

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    UUID attacker = new UUID(1L, seed);
                    for (int i = 0; i < perThreadOps; i++) {
                        UUID victim = UUID.nameUUIDFromBytes(("v-" + seed + "-" + (i % 128)).getBytes());
                        dh.record(victim, attacker, 1_000L + i);
                        dh.lastPlayerToHit(victim, 1_000L + i);
                        if ((i & 31) == 0) {
                            dh.forget(victim);
                        }
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "damage-history-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        start.countDown();
        done.await();

        assertThat(firstErr.get()).isNull();
        assertThat(dh.size()).isLessThanOrEqualTo(64 + DamageHistory.EVICT_STRIDE);
        dh.clear();
        assertThat(dh.size()).isZero();
    }

    @Test
    void concurrentClearAndRecordKeepsTimestampCapBound() throws Exception {
        int cap = 64;
        DamageHistory dh = new DamageHistory(60_000L, cap);

        final int threads = 8;
        final int perThreadOps = 2_000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> firstErr = new AtomicReference<>();

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    UUID attacker = new UUID(2L, seed);
                    for (int i = 0; i < perThreadOps; i++) {
                        UUID victim = UUID.nameUUIDFromBytes(("c-" + seed + "-" + i).getBytes());
                        dh.record(victim, attacker, 50_000L - i);
                        if ((i & 63) == 0) {
                            dh.clear();
                        }
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "damage-history-clear-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("clear/record must not deadlock")
                .isTrue();
        assertThat(firstErr.get()).isNull();

        UUID attacker = UUID.fromString("33333333-3333-3333-3333-333333333333");
        for (int i = 0; i < cap + DamageHistory.EVICT_STRIDE; i++) {
            dh.record(UUID.nameUUIDFromBytes(("storm-" + i).getBytes()), attacker, 1_000L + i);
        }
        assertThat(dh.size()).isLessThanOrEqualTo(cap + DamageHistory.EVICT_STRIDE);
        assertThat(dh.evictions()).isGreaterThan(0L);
    }
}
