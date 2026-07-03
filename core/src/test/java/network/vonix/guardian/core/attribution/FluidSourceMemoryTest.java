/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.3.1 X3 — regression tests for the bucket-empty → fluid-flow traceback
 * memory. See {@link FluidSourceMemory} for the semantics under test.
 */
class FluidSourceMemoryTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void lookupWithinRadiusReturnsRecordedPlayer() {
        FluidSourceMemory mem = new FluidSourceMemory();
        UUID alice = UUID.randomUUID();
        mem.recordBucketEmpty(OVERWORLD, 100, 64, 100, alice, "Alice", 10_000L);

        FluidSourceMemory.Record hit = mem.lookup(OVERWORLD, 103, 64, 100, 15_000L);
        assertThat(hit).isNotNull();
        assertThat(hit.actorUuid).isEqualTo(alice);
        assertThat(hit.actorName).isEqualTo("Alice");
    }

    @Test
    void lookupOutsideRadiusReturnsNull() {
        FluidSourceMemory mem = new FluidSourceMemory();
        mem.recordBucketEmpty(OVERWORLD, 0, 64, 0, UUID.randomUUID(), "A", 0L);
        // 8-block Manhattan cutoff — (5,0,4) is dist 9, must miss.
        assertThat(mem.lookup(OVERWORLD, 5, 64, 4, 1_000L)).isNull();
    }

    @Test
    void lookupPrefersClosestSource() {
        FluidSourceMemory mem = new FluidSourceMemory();
        UUID far = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        mem.recordBucketEmpty(OVERWORLD, 0, 64, 0, far, "Far", 0L);
        mem.recordBucketEmpty(OVERWORLD, 2, 64, 0, near, "Near", 500L);
        FluidSourceMemory.Record hit = mem.lookup(OVERWORLD, 3, 64, 0, 1_000L);
        assertThat(hit).isNotNull();
        assertThat(hit.actorUuid).isEqualTo(near);
    }

    @Test
    void lookupIgnoresCrossWorldRecord() {
        FluidSourceMemory mem = new FluidSourceMemory();
        mem.recordBucketEmpty(NETHER, 0, 64, 0, UUID.randomUUID(), "A", 0L);
        assertThat(mem.lookup(OVERWORLD, 0, 64, 0, 1_000L)).isNull();
    }

    @Test
    void expiredEntriesAreDroppedOnLookup() {
        FluidSourceMemory mem = new FluidSourceMemory();
        mem.recordBucketEmpty(OVERWORLD, 0, 64, 0, UUID.randomUUID(), "A", 0L);
        long after = FluidSourceMemory.TTL_MS + 5_000L;
        assertThat(mem.lookup(OVERWORLD, 0, 64, 0, after)).isNull();
        // Expired entry should have been evicted opportunistically.
        assertThat(mem.size()).isEqualTo(0);
    }

    @Test
    void ringBufferEvictsOldestEntriesOverCap() {
        FluidSourceMemory mem = new FluidSourceMemory(FluidSourceMemory.TTL_MS,
                FluidSourceMemory.MAX_RADIUS_MANHATTAN, 4);
        // Insert 8 entries at distinct positions >8 blocks apart so radius doesn't
        // let evicted entries be found as neighbours of the check position.
        for (int i = 0; i < 8; i++) {
            mem.recordBucketEmpty(OVERWORLD, i * 100, 64, 0, UUID.randomUUID(), "A" + i, i);
        }
        assertThat(mem.size()).isEqualTo(4);
        // Oldest (i=0..3, at x=0..300) must have been evicted; newest (i=4..7, at x=400..700) survive.
        assertThat(mem.lookup(OVERWORLD, 0, 64, 0, 100L)).isNull();
        assertThat(mem.lookup(OVERWORLD, 300, 64, 0, 100L)).isNull();
        assertThat(mem.lookup(OVERWORLD, 400, 64, 0, 100L)).isNotNull();
        assertThat(mem.lookup(OVERWORLD, 700, 64, 0, 100L)).isNotNull();
    }

    @Test
    void samePositionOverwrites() {
        FluidSourceMemory mem = new FluidSourceMemory();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        mem.recordBucketEmpty(OVERWORLD, 0, 64, 0, first, "First", 0L);
        mem.recordBucketEmpty(OVERWORLD, 0, 64, 0, second, "Second", 100L);
        assertThat(mem.size()).isEqualTo(1);
        FluidSourceMemory.Record hit = mem.lookup(OVERWORLD, 0, 64, 0, 200L);
        assertThat(hit).isNotNull();
        assertThat(hit.actorUuid).isEqualTo(second);
    }

    @Test
    void constructorRejectsInvalidArgs() {
        assertThatThrownBy(() -> new FluidSourceMemory(0, 4, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FluidSourceMemory(1000, -1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FluidSourceMemory(1000, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ttlIsExactlyTwoMinutes() {
        assertThat(FluidSourceMemory.TTL_MS).isEqualTo(2L * 60L * 1000L);
    }
}
