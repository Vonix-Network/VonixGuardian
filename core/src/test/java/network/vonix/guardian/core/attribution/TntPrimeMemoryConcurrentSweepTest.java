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
 * v1.3.6 CC2 (P2-9) regression: pre-1.3.6 the {@code TntPrimeMemory}
 * amortized sweep used a {@code volatile Iterator} shared across all threads
 * calling {@link TntPrimeMemory#record}, which could ConcurrentModification
 * when two callers interleaved. This test hammers record() from multiple
 * threads with a small map and asserts no {@link RuntimeException} escapes.
 *
 * <p>The synchronized-sweepLock guard added in CC2 eliminates the race.
 */
final class TntPrimeMemoryConcurrentSweepTest {

    @Test
    void concurrent_record_does_not_throw_from_amortized_sweep() throws Exception {
        // Small maxEntries so the sweep and hard-evict paths both fire often.
        TntPrimeMemory mem = new TntPrimeMemory(60_000L, 128, System::currentTimeMillis);

        final int threads = 8;
        final int perThreadOps = 5_000;
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
                        int x = (seed * perThreadOps + i) % 4096;
                        mem.record("world", x, 64, 0,
                                TntPrimeMemory.PrimeRecord.player(actor, "T" + seed, System.currentTimeMillis()));
                        if ((i & 15) == 0) {
                            mem.consume("world", x, 64, 0);
                        }
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "tnt-prime-record-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        start.countDown();
        done.await();

        assertThat(firstErr.get())
                .as("no exception must escape record()/consume() under concurrent load")
                .isNull();
        assertThat(mem.size()).isLessThanOrEqualTo(128 + TntPrimeMemory.HARD_EVICT_ARBITRARY_CAP);
    }

    @Test
    void concurrentClearAndRecordKeepsCapBound() throws Exception {
        int cap = 128;
        AtomicLong clock = new AtomicLong(1_000L);
        TntPrimeMemory mem = new TntPrimeMemory(60_000L, cap, clock::get);

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
                    UUID actor = new UUID(3L, seed);
                    for (int i = 0; i < perThreadOps; i++) {
                        int x = seed * perThreadOps + i;
                        mem.record("world", x, 64, 0,
                                TntPrimeMemory.PrimeRecord.player(actor, "C" + seed, clock.incrementAndGet()));
                        if ((i & 63) == 0) {
                            mem.clear();
                        }
                    }
                } catch (Throwable err) {
                    firstErr.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            }, "tnt-prime-clear-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("clear/record must not deadlock")
                .isTrue();
        assertThat(firstErr.get()).isNull();

        UUID actor = UUID.fromString("44444444-4444-4444-4444-444444444444");
        int storm = cap + TntPrimeMemory.HARD_EVICT_STRIDE;
        for (int i = 0; i < storm; i++) {
            mem.record("world", 1_000_000 + i, 64, 0,
                    TntPrimeMemory.PrimeRecord.player(actor, "Storm", clock.incrementAndGet()));
        }
        int upperBound = cap + TntPrimeMemory.HARD_EVICT_STRIDE
                + TntPrimeMemory.HARD_EVICT_ARBITRARY_CAP;
        assertThat(mem.size()).isLessThanOrEqualTo(upperBound);
    }
}
