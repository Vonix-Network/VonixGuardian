package network.vonix.guardian.core.queue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EntityBlockChangeCoalescerTest {

    @Test
    void repeatedSameActorAndCoordinateWithinWindowIsSuppressed() {
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(60_000L, 128);

        assertThat(c.shouldLog("dragon", "world", 1, 64, 1)).isTrue();
        assertThat(c.shouldLog("dragon", "world", 1, 64, 1)).isFalse();

        assertThat(c.misses()).isEqualTo(1);
        assertThat(c.hits()).isEqualTo(1);
        assertThat(c.size()).isEqualTo(1);
    }

    @Test
    void uniqueFreshKeysDoNotGrowPastConfiguredCap() {
        int max = 32;
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(60_000L, max);

        for (int i = 0; i < max * 10; i++) {
            c.shouldLog("dragon-" + i, "world", i, 64, i);
        }

        assertThat(c.size()).isLessThanOrEqualTo(max);
        assertThat(c.capDrops()).isGreaterThan(0L);
    }

    @Test
    void injectedClockExpiresDuplicateWindowWithoutSleep() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0L);
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(1_000L, 128, clock::get);

        assertThat(c.shouldLog("dragon", "world", 1, 64, 1)).isTrue();
        clock.set(TimeUnit.MILLISECONDS.toNanos(999L));
        assertThat(c.shouldLog("dragon", "world", 1, 64, 1)).isFalse();
        // In-window hits refresh last-seen; expiry is measured from that refresh.
        clock.set(TimeUnit.MILLISECONDS.toNanos(999L) + TimeUnit.MILLISECONDS.toNanos(1_000L));
        assertThat(c.shouldLog("dragon", "world", 1, 64, 1)).isTrue();

        assertThat(c.misses()).isEqualTo(2L);
        assertThat(c.hits()).isEqualTo(1L);
        assertThat(c.size()).isEqualTo(1);
        assertThat(c.capDrops()).isZero();
    }

    @Test
    void differentActorOrCoordinateIsNotSuppressed() {
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(60_000L, 128);

        assertThat(c.shouldLog("dragon-a", "world", 1, 64, 1)).isTrue();
        assertThat(c.shouldLog("dragon-b", "world", 1, 64, 1)).isTrue();
        assertThat(c.shouldLog("dragon-a", "world", 2, 64, 1)).isTrue();
        assertThat(c.shouldLog("dragon-a", "nether", 1, 64, 1)).isTrue();

        assertThat(c.misses()).isEqualTo(4L);
        assertThat(c.hits()).isZero();
        assertThat(c.size()).isEqualTo(4);
    }

    @Test
    void zeroSweepBackoffUsesInjectedClockAndPreservesCapMetrics() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0L);
        int max = 8;
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(60_000L, max, clock::get);

        int admitted = 0;
        for (int i = 0; i < max; i++) {
            if (c.shouldLog("dragon-" + i, "world", i, 64, i)) {
                admitted++;
            }
        }
        assertThat(admitted).isEqualTo(max);
        assertThat(c.size()).isEqualTo(max);

        assertThat(c.shouldLog("overflow-0", "world", 100, 64, 100)).isFalse();
        long dropsAfterFirst = c.capDrops();
        assertThat(dropsAfterFirst).isGreaterThan(0L);

        clock.addAndGet(EntityBlockChangeCoalescer.ZERO_SWEEP_BACKOFF_NS - 1L);
        assertThat(c.shouldLog("overflow-1", "world", 101, 64, 101)).isFalse();
        assertThat(c.capDrops()).isGreaterThan(dropsAfterFirst);

        clock.addAndGet(2L);
        assertThat(c.shouldLog("overflow-2", "world", 102, 64, 102)).isFalse();
        assertThat(c.size()).isLessThanOrEqualTo(max);
        assertThat(c.hits()).isGreaterThan(0L);
        assertThat(c.misses()).isEqualTo(max);
    }
}
