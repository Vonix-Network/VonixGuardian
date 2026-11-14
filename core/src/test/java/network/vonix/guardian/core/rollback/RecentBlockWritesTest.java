package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The low-level block-write hook and the attributed event layer both observe the same
 * change. Without suppression every block a player breaks would be logged twice — once
 * with their name, once as {@code UNKNOWN} — which is worse than not having the hook.
 *
 * <p>{@link RecentBlockWrites} is that suppression: a short-lived record of positions
 * the attributed layer just wrote.</p>
 */
class RecentBlockWritesTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void attributedPositionSuppressesTheFallbackHook() {
        RecentBlockWrites rec = new RecentBlockWrites();
        long now = 1_000_000L;
        rec.record(WORLD, 10, 64, 20, now);
        assertThat(rec.recentlyAttributed(WORLD, 10, 64, 20, now + 5L)).isTrue();
    }

    @Test
    void differentPositionIsNotSuppressed() {
        RecentBlockWrites rec = new RecentBlockWrites();
        long now = 1_000_000L;
        rec.record(WORLD, 10, 64, 20, now);
        assertThat(rec.recentlyAttributed(WORLD, 11, 64, 20, now)).isFalse();
        assertThat(rec.recentlyAttributed(WORLD, 10, 65, 20, now)).isFalse();
        assertThat(rec.recentlyAttributed(WORLD, 10, 64, 21, now)).isFalse();
    }

    /** The same coordinates in another dimension are a different block. */
    @Test
    void samePositionInAnotherWorldIsNotSuppressed() {
        RecentBlockWrites rec = new RecentBlockWrites();
        long now = 1_000_000L;
        rec.record(WORLD, 10, 64, 20, now);
        assertThat(rec.recentlyAttributed("minecraft:the_nether", 10, 64, 20, now)).isFalse();
    }

    /**
     * Suppression must expire, or a genuine later change at the same position — the
     * griefing case — would be silently dropped forever.
     */
    @Test
    void suppressionExpiresSoLaterChangesAreStillRecorded() {
        RecentBlockWrites rec = new RecentBlockWrites(500L, 64);
        long now = 1_000_000L;
        rec.record(WORLD, 5, 70, 5, now);
        assertThat(rec.recentlyAttributed(WORLD, 5, 70, 5, now + 400L)).isTrue();
        assertThat(rec.recentlyAttributed(WORLD, 5, 70, 5, now + 600L))
            .as("after the window the position must be recordable again")
            .isFalse();
    }

    /** A long-running server must not accumulate positions without bound. */
    @Test
    void capacityIsBounded() {
        RecentBlockWrites rec = new RecentBlockWrites(60_000L, 16);
        long now = 1_000_000L;
        for (int i = 0; i < 500; i++) {
            rec.record(WORLD, i, 64, 0, now + i);
        }
        assertThat(rec.size())
            .as("an unbounded map is a memory leak on a busy server")
            .isLessThanOrEqualTo(16);
    }

    @Test
    void negativeAndLargeCoordinatesDoNotCollide() {
        RecentBlockWrites rec = new RecentBlockWrites();
        long now = 1_000_000L;
        rec.record(WORLD, -30_000_000, 320, -30_000_000, now);
        assertThat(rec.recentlyAttributed(WORLD, -30_000_000, 320, -30_000_000, now)).isTrue();
        assertThat(rec.recentlyAttributed(WORLD, 30_000_000, 320, 30_000_000, now)).isFalse();
    }

    @Test
    void clearDropsEverything() {
        RecentBlockWrites rec = new RecentBlockWrites();
        rec.record(WORLD, 1, 2, 3, 100L);
        rec.clear();
        assertThat(rec.recentlyAttributed(WORLD, 1, 2, 3, 100L)).isFalse();
        assertThat(rec.size()).isZero();
    }
}
