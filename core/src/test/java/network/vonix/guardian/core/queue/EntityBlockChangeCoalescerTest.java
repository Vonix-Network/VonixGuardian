package network.vonix.guardian.core.queue;

import org.junit.jupiter.api.Test;

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
}
