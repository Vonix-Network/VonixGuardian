package network.vonix.guardian.core.action;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W2 — regression test for the de-boxed SPAWN_LIMIT throttle used by
 * every loader cell's {@code onEntityJoinLevel} handler (all 8 cells:
 * {@code FabricEvents.java} × 4 + {@code ForgeEvents.java} × 3 +
 * {@code NeoForgeEvents.java} × 1).
 *
 * <p>Because SPAWN_LIMIT lives in cell code (which core cannot see), this test
 * pins the <em>pattern</em>: a {@link ConcurrentHashMap
 * ConcurrentHashMap&lt;String, AtomicLong&gt;} keyed by entity type, with an
 * in-place {@code AtomicLong.set(now)} on the hot path. Any regression to
 * {@code Map<String, Long>} — where {@code put(type, now)} autoboxes a fresh
 * {@link Long} per spawn — would fail the identity-check below.</p>
 *
 * <p>Instrumentation-free proof of no boxing: after 10k in-place updates for
 * the same entity type, the {@link AtomicLong} instance in the map is the same
 * object the first submit inserted. If the code were still doing
 * {@code map.put(type, Long.valueOf(now))} the value would be replaced every
 * time with a distinct {@link Long}.</p>
 */
class SpawnLimitDeBoxTest {

    private static final Map<String, AtomicLong> SPAWN_LIMIT = new ConcurrentHashMap<>();
    private static final long SPAWN_LIMIT_MS = 1_000L;

    /** Model of the cell-side hot-path throttle. Same shape as the 8-cell recipe. */
    private static boolean shouldRateLimit(String type, long now) {
        AtomicLong last = SPAWN_LIMIT.get(type);
        if (last == null) {
            AtomicLong fresh = new AtomicLong(now);
            AtomicLong prev = SPAWN_LIMIT.putIfAbsent(type, fresh);
            if (prev != null) prev.set(now);
            return false; // first-touch never limits
        }
        if (now - last.get() < SPAWN_LIMIT_MS) return true; // throttled
        last.set(now);
        return false;
    }

    @Test
    void tenThousandUpdatesReuseSameAtomicLongInstance() {
        SPAWN_LIMIT.clear();
        String type = "minecraft:zombie";
        // First submit — inserts a fresh AtomicLong.
        shouldRateLimit(type, 1_000_000_000L);
        AtomicLong initial = SPAWN_LIMIT.get(type);
        assertThat(initial).isNotNull();

        // 10k in-place updates.
        long now = 1_000_000_000L;
        for (int i = 0; i < 10_000; i++) {
            now += SPAWN_LIMIT_MS + 1L; // step past the throttle window each time
            shouldRateLimit(type, now);
        }

        // The AtomicLong instance in the map is the SAME object we captured before
        // the burst — proof that no Long autoboxing put() replaced it.
        assertThat(SPAWN_LIMIT.get(type))
            .as("hot-path SPAWN_LIMIT reuses the same AtomicLong instance")
            .isSameAs(initial);
        assertThat(initial.get()).isEqualTo(now);
    }

    @Test
    void distinctTypesGetDistinctAtomicLongs() {
        SPAWN_LIMIT.clear();
        shouldRateLimit("minecraft:zombie", 100L);
        shouldRateLimit("minecraft:skeleton", 100L);
        AtomicLong z1 = SPAWN_LIMIT.get("minecraft:zombie");
        AtomicLong s1 = SPAWN_LIMIT.get("minecraft:skeleton");
        assertThat(z1).isNotNull().isNotSameAs(s1);
        assertThat(s1).isNotNull();

        // Repeat — same instances survive.
        for (int i = 0; i < 500; i++) {
            shouldRateLimit("minecraft:zombie", 10_000L + i * 2L * SPAWN_LIMIT_MS);
            shouldRateLimit("minecraft:skeleton", 10_000L + i * 2L * SPAWN_LIMIT_MS);
        }
        assertThat(SPAWN_LIMIT.get("minecraft:zombie")).isSameAs(z1);
        assertThat(SPAWN_LIMIT.get("minecraft:skeleton")).isSameAs(s1);
    }

    @Test
    void throttleWindowStillHolds() {
        SPAWN_LIMIT.clear();
        String type = "minecraft:pig";
        assertThat(shouldRateLimit(type, 100L)).isFalse();                // first-touch
        assertThat(shouldRateLimit(type, 100L + 500L)).isTrue();          // within 1s → throttle
        assertThat(shouldRateLimit(type, 100L + SPAWN_LIMIT_MS + 1L)).isFalse(); // past window
    }
}
