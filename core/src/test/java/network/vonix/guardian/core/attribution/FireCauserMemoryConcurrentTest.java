/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fabric-style concurrent record/consume against the mutable probe key and
 * bounded eviction ring. Uses an injected clock — no sleeps.
 */
final class FireCauserMemoryConcurrentTest {

    @Test
    void concurrentRecordConsumePeekDoesNotThrow() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = new FireCauserMemory(60_000L, 128, 2, clock::get);

        final int threads = 8;
        final int perThreadOps = 4_000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> firstErr = new AtomicReference<>();

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    UUID actor = new UUID(0L, seed);
                    for (int i = 0; i < perThreadOps; i++) {
                        long now = clock.incrementAndGet();
                        int x = (seed * perThreadOps + i) % 512;
                        FireCauserMemory.CauserRecord rec = (i & 1) == 0
                                ? FireCauserMemory.CauserRecord.allowlisted(
                                        actor, "T" + seed, "mod:dragon", "#entity", now, now)
                                : FireCauserMemory.CauserRecord.suppressed("mod:dragon", now);
                        mem.record("world", x, 64, 0, rec);
                        if ((i & 7) == 0) {
                            mem.peek("world", x, 64, 0);
                        }
                        if ((i & 15) == 0) {
                            mem.consume("world", x, 64, 0);
                        }
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "fire-causer-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        start.countDown();
        done.await();

        assertThat(firstErr.get()).isNull();
        mem.clear();
        assertThat(mem.size()).isZero();
    }

    @Test
    void concurrentClearAndRecordKeepsCapBound() throws Exception {
        int cap = 128;
        AtomicLong clock = new AtomicLong(1_000L);
        FireCauserMemory mem = new FireCauserMemory(60_000L, cap, 2, clock::get);

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
                    UUID actor = new UUID(5L, seed);
                    for (int i = 0; i < perThreadOps; i++) {
                        long now = clock.incrementAndGet();
                        int x = seed * perThreadOps + i;
                        FireCauserMemory.CauserRecord rec = (i & 1) == 0
                                ? FireCauserMemory.CauserRecord.allowlisted(
                                        actor, "C" + seed, "mod:dragon", "#entity", now, now)
                                : FireCauserMemory.CauserRecord.suppressed("mod:dragon", now);
                        mem.record("world", x, 64, 0, rec);
                        if ((i & 63) == 0) {
                            mem.clear();
                        }
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "fire-causer-clear-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("clear/record must not deadlock")
                .isTrue();
        assertThat(firstErr.get()).isNull();

        UUID actor = UUID.fromString("55555555-5555-5555-5555-555555555555");
        int storm = cap + FireCauserMemory.HARD_EVICT_STRIDE;
        for (int i = 0; i < storm; i++) {
            long now = clock.incrementAndGet();
            mem.record("world", 1_000_000 + i, 64, 0,
                    FireCauserMemory.CauserRecord.allowlisted(
                            actor, "Storm", "mod:dragon", "#entity", now, now));
        }
        int upperBound = cap + FireCauserMemory.HARD_EVICT_STRIDE
                + FireCauserMemory.HARD_EVICT_ARBITRARY_CAP;
        assertThat(mem.size()).isLessThanOrEqualTo(upperBound);
    }
}
