package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W3 — regression test proving that {@link ExplosionJoinWorker}-emitted
 * chunked EXPLOSION rows parse cleanly through {@link ExplosionAffectedList}.
 *
 * <p>W3's off-thread join splits a large affected-block list into multiple
 * EXPLOSION rows sharing the same center + source. Each chunk row's
 * {@code affected} payload is itself a fully-valid affected-list — no schema
 * change was needed. This test locks in that transparency.</p>
 */
class ExplosionAffectedListChunkedParsingTest {

    @Test
    void chunkedEmission_roundTripsPerChunk() {
        // Simulate two chunk rows sharing the same (center, sourceTag).
        String chunk1 = "10:64:5=minecraft:stone,11:64:5=minecraft:dirt,12:64:5=minecraft:sand";
        String chunk2 = "13:64:5=minecraft:stone,14:64:5=minecraft:gravel";
        ExplosionAffectedList a = ExplosionAffectedList.parse(chunk1);
        ExplosionAffectedList b = ExplosionAffectedList.parse(chunk2);
        assertThat(a.size()).isEqualTo(3);
        assertThat(b.size()).isEqualTo(2);
        assertThat(a.serialize()).isEqualTo(chunk1);
        assertThat(b.serialize()).isEqualTo(chunk2);
    }

    @Test
    void bothChunks_reachWithinRadius() {
        // Rollback semantics: user issues r:10 centered on (10,64,5). Both
        // chunk rows expand to blocks all within that box.
        String chunk1 = "10:64:5=minecraft:stone,15:64:5=minecraft:dirt";
        String chunk2 = "8:64:6=minecraft:sand,20:64:8=minecraft:gravel";
        assertThat(ExplosionAffectedList.parse(chunk1).anyWithinRadius(10, 64, 5, 10)).isTrue();
        assertThat(ExplosionAffectedList.parse(chunk2).anyWithinRadius(10, 64, 5, 10)).isTrue();
        // Neither chunk overlaps a far-away query:
        assertThat(ExplosionAffectedList.parse(chunk1).anyWithinRadius(1000, 64, 5, 10)).isFalse();
        assertThat(ExplosionAffectedList.parse(chunk2).anyWithinRadius(1000, 64, 5, 10)).isFalse();
    }

    @Test
    void largeChunk_underCap() {
        // Emit a chunk exactly at MAX_ENTRIES_PER_CHUNK size (96 entries).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 96; i++) {
            if (i > 0) sb.append(',');
            sb.append(i).append(":64:0=minecraft:stone");
        }
        ExplosionAffectedList list = ExplosionAffectedList.parse(sb.toString());
        assertThat(list.size()).isEqualTo(96);
        assertThat(list.serialize().length()).isLessThanOrEqualTo(4096);
    }
}
