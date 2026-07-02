package network.vonix.guardian.core.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorldEditRegionResolver}. Because WorldEdit is a soft
 * dependency it is guaranteed NOT to be on the {@code core:test} classpath, so
 * every reflective lookup must fail cleanly and produce {@link Optional#empty()}.
 * These tests therefore only exercise the failure paths — the success path is
 * exercised in integration tests against a real WorldEdit jar.
 */
class WorldEditRegionResolverTest {

    @BeforeEach
    void reset() {
        WorldEditRegionResolver.resetForTests();
        WorldEditRegionResolver.setNameResolver(null);
    }

    @Test
    void resolveSelection_worldEditAbsent_returnsEmpty() {
        // WorldEdit is not on the core test classpath.
        Optional<WorldEditRegionResolver.Box> box =
            WorldEditRegionResolver.resolveByName("Notch");
        assertNotNull(box);
        assertTrue(box.isEmpty(), "Expected empty when WorldEdit is not installed");
    }

    @Test
    void resolveSelection_nullUuid_returnsEmpty() {
        assertTrue(WorldEditRegionResolver.resolveSelection(null).isEmpty());
    }

    @Test
    void resolveSelection_nullName_returnsEmpty() {
        assertTrue(WorldEditRegionResolver.resolveByName(null).isEmpty());
        assertTrue(WorldEditRegionResolver.resolveByName("").isEmpty());
    }

    @Test
    void resolveSelection_noNameResolver_returnsEmpty() {
        // With no NameResolver installed, resolveSelection(UUID) has nothing
        // to feed WorldEdit's LocalSession#findByName — must degrade to empty.
        Optional<WorldEditRegionResolver.Box> box =
            WorldEditRegionResolver.resolveSelection(UUID.randomUUID());
        assertTrue(box.isEmpty());
    }

    @Test
    void resolveSelection_throwingNameResolver_returnsEmpty() {
        WorldEditRegionResolver.setNameResolver(uuid -> {
            throw new RuntimeException("boom");
        });
        Optional<WorldEditRegionResolver.Box> box =
            WorldEditRegionResolver.resolveSelection(UUID.randomUUID());
        assertTrue(box.isEmpty(), "Throwing resolver must not propagate");
    }

    @Test
    void isAvailable_returnsFalseWhenWorldEditAbsent() {
        assertFalse(WorldEditRegionResolver.isAvailable(),
            "WorldEdit is not on core test classpath — must report unavailable");
    }

    @Test
    void resolveSelection_boxRecordEqualityAndAccessors() {
        // Purely a sanity check on the value type so the record cannot be
        // silently reshaped without breaking the compiler-level contract with
        // QueryCompiler.
        WorldEditRegionResolver.Box b = new WorldEditRegionResolver.Box(1, 2, 3, 4, 5, 6);
        assertSame(1, b.minX());
        assertSame(2, b.minY());
        assertSame(3, b.minZ());
        assertSame(4, b.maxX());
        assertSame(5, b.maxY());
        assertSame(6, b.maxZ());
    }
}
