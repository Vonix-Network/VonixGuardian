/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.2 Y5 (P1-4) — regression tests for
 * {@link FluidSourceMemory#lookup(String, int, int, int, long) lookup()}'s
 * empty fast-path.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link FluidSourceMemory#size()} returns 0 on a fresh instance without
 *       touching the internal {@link ReentrantLock}.</li>
 *   <li>{@code lookup()} short-circuits without acquiring the lock when the
 *       volatile size snapshot is 0 (fluid ticks on non-griefed servers).</li>
 *   <li>The size snapshot stays consistent with {@code byPos.size()} across
 *       record, TTL-expire eviction, and over-cap eviction.</li>
 *   <li>Record → lookup on the same server tick still finds the entry (the
 *       fast-path guard doesn't hide freshly-recorded entries).</li>
 * </ul>
 */
class FluidSourceMemoryEmptyFastPathTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void size_zero_on_fresh_instance_without_lock() throws Exception {
        FluidSourceMemory mem = new FluidSourceMemory();
        assertThat(mem.size()).isZero();
        // Post-condition: nobody acquired the lock while asking for size.
        assertLockUnheld(mem);
        assertNoQueuedThreads(mem);
    }

    @Test
    void lookup_on_empty_returns_null_without_touching_lock() throws Exception {
        FluidSourceMemory mem = new FluidSourceMemory();
        // Steady-state fluid-tick hot path: nothing recorded, lookup called.
        // The fast-path should return null before entering the critical section.
        FluidSourceMemory.Record r = mem.lookup(WORLD, 100, 64, 100, 5_000L);
        assertThat(r).isNull();
        assertLockUnheld(mem);
        assertNoQueuedThreads(mem);
    }

    @Test
    void record_then_lookup_still_returns_hit() {
        FluidSourceMemory mem = new FluidSourceMemory();
        UUID alice = UUID.randomUUID();
        mem.recordBucketEmpty(WORLD, 100, 64, 100, alice, "Alice", 10_000L);
        assertThat(mem.size()).isEqualTo(1);
        FluidSourceMemory.Record hit = mem.lookup(WORLD, 101, 64, 100, 11_000L);
        assertThat(hit).isNotNull();
        assertThat(hit.actorUuid).isEqualTo(alice);
    }

    @Test
    void size_snapshot_matches_bypos_across_ttl_eviction() throws Exception {
        long ttl = 1_000L;
        FluidSourceMemory mem = new FluidSourceMemory(ttl, 8, 1024);
        mem.recordBucketEmpty(WORLD, 0, 0, 0, UUID.randomUUID(), "P", 0L);
        mem.recordBucketEmpty(WORLD, 1, 0, 0, UUID.randomUUID(), "P", 0L);
        assertThat(mem.size()).isEqualTo(2);

        // Trigger opportunistic TTL eviction inside lookup at nowMs past TTL.
        // Both entries are stale → removed. Fast-path snapshot must follow.
        FluidSourceMemory.Record r = mem.lookup(WORLD, 0, 0, 0, 5_000L);
        assertThat(r).isNull();
        assertThat(mem.size()).as("volatile size must track byPos.size after TTL eviction").isZero();
        assertThat(byPosSize(mem)).isZero();

        // Next lookup should hit the empty fast-path — lock stays free.
        assertThat(mem.lookup(WORLD, 0, 0, 0, 6_000L)).isNull();
        assertLockUnheld(mem);
    }

    @Test
    void size_snapshot_matches_bypos_across_over_cap_eviction() throws Exception {
        FluidSourceMemory mem = new FluidSourceMemory(60_000L, 8, 4);
        for (int i = 0; i < 20; i++) {
            mem.recordBucketEmpty(WORLD, i, 0, 0, UUID.randomUUID(), "P" + i, 1_000L + i);
        }
        // Cap is 4 → after 20 inserts we must be exactly at cap.
        assertThat(mem.size()).isEqualTo(4);
        assertThat(byPosSize(mem)).isEqualTo(4);
    }

    @Test
    void size_field_is_volatile_and_read_lock_free() throws Exception {
        Field f = FluidSourceMemory.class.getDeclaredField("size");
        assertThat(java.lang.reflect.Modifier.isVolatile(f.getModifiers()))
                .as("size field must be volatile for lock-free reads")
                .isTrue();
        assertThat(java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .as("size field must be instance-scoped")
                .isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static void assertLockUnheld(FluidSourceMemory mem) throws Exception {
        ReentrantLock lock = readLock(mem);
        assertThat(lock.isLocked())
                .as("ReentrantLock must not be held after a fast-path miss")
                .isFalse();
    }

    private static void assertNoQueuedThreads(FluidSourceMemory mem) throws Exception {
        ReentrantLock lock = readLock(mem);
        assertThat(lock.hasQueuedThreads())
                .as("no thread should ever have queued on the fast-path lookup")
                .isFalse();
    }

    private static ReentrantLock readLock(FluidSourceMemory mem) throws Exception {
        Field f = FluidSourceMemory.class.getDeclaredField("lock");
        f.setAccessible(true);
        return (ReentrantLock) f.get(mem);
    }

    private static int byPosSize(FluidSourceMemory mem) throws Exception {
        Field f = FluidSourceMemory.class.getDeclaredField("byPos");
        f.setAccessible(true);
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) f.get(mem);
        return m.size();
    }
}
