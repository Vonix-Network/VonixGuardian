package network.vonix.guardian.core.queue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coalescer is a throttle, not a mute.
 *
 * <p>Suppression was measured from the last event of <em>any</em> kind: an in-window
 * hit pushed the stored timestamp forward. An entity that kept touching the same
 * coordinate &mdash; the normal case for a mob sitting on the block it keeps trying to
 * destroy &mdash; therefore suppressed its own coordinate forever. The destruction was
 * recorded once and every later one was dropped, so a rollback restored a state that
 * never existed. The window must be measured from the last event actually recorded.</p>
 */
class EntityBlockChangeCoalescerWindowTest {

    @Test
    void continuousHitsDoNotExtendSuppressionIndefinitely() {
        AtomicLong clock = new AtomicLong(0L);
        long windowMs = 500L;
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(windowMs, 1024, clock::get);

        assertThat(c.shouldLog("dragon", "world", 1, 64, 1))
                .as("first sighting must be recorded")
                .isTrue();

        // One hit every 400ms — inside the 500ms window, forever. Under the old
        // refresh-on-hit behaviour this suppressed the coordinate for all time.
        long step = TimeUnit.MILLISECONDS.toNanos(400L);
        int suppressed = 0;
        for (int i = 0; i < 100; i++) {
            clock.addAndGet(step);
            if (!c.shouldLog("dragon", "world", 1, 64, 1)) {
                suppressed++;
            }
        }
        assertThat(suppressed)
                .as("a sustained hit stream must still produce periodic rows")
                .isLessThan(100);

        // 100 hits at 400ms = 40s of wall time. Suppression is bounded by the
        // 500ms window, so at most two consecutive hits per recorded row.
        assertThat(c.misses())
                .as("a 40s sustained stream must record many rows, not one")
                .isGreaterThan(20L);
        assertThat(c.hits() + c.misses()).isEqualTo(101L);
    }

    @Test
    void windowIsMeasuredFromTheRecordedEventNotFromTheLastHit() {
        AtomicLong clock = new AtomicLong(0L);
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(1_000L, 128, clock::get);

        assertThat(c.shouldLog("dragon", "world", 0, 64, 0)).isTrue();
        clock.set(TimeUnit.MILLISECONDS.toNanos(900L));
        assertThat(c.shouldLog("dragon", "world", 0, 64, 0))
                .as("inside the window: suppressed")
                .isFalse();

        // 100ms later the window opened by the recorded event has elapsed. The
        // suppressed hit must not have pushed the deadline out to 1_900ms.
        clock.set(TimeUnit.MILLISECONDS.toNanos(1_100L));
        assertThat(c.shouldLog("dragon", "world", 0, 64, 0))
                .as("suppression must expire one window after the recorded row")
                .isTrue();
    }

    @Test
    void metricsStillBalanceUnderSustainedSuppression() {
        AtomicLong clock = new AtomicLong(0L);
        EntityBlockChangeCoalescer c = new EntityBlockChangeCoalescer(100L, 64, clock::get);

        int allowed = 0;
        int total = 0;
        for (int i = 0; i < 50; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(10L));
            if (c.shouldLog("dragon", "world", 5, 64, 5)) {
                allowed++;
            }
            total++;
        }

        assertThat(c.misses()).as("misses count exactly the allowed rows").isEqualTo(allowed);
        assertThat(c.hits()).as("hits count exactly the suppressed rows").isEqualTo(total - allowed);
        assertThat(c.misses()).isGreaterThan(0L);
        assertThat(c.hits()).isGreaterThan(0L);
        assertThat(c.size()).isEqualTo(1);
    }
}
