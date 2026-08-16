package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StrongholdGenerationSafetyTest {

    @Test
    void failedWarmupDoesNotLatchGeneratedFlag() {
        RecordingGenerator generator = new RecordingGenerator();
        generator.failNext = true;
        try {
            generator.ensureStructuresGenerated();
            fail("warmup failure must surface");
        } catch (IllegalStateException ignored) {
        }
        assertFalse(generator.hasGeneratedPositions, "failed generatePositions must not set the latch");

        generator.failNext = false;
        generator.ensureStructuresGenerated();
        assertTrue(generator.hasGeneratedPositions);
        assertTrue(generator.calls == 2, "successful retry must run after a failed warmup");
    }

    @Test
    void successfulWarmupRunsOnce() {
        RecordingGenerator generator = new RecordingGenerator();
        generator.ensureStructuresGenerated();
        generator.ensureStructuresGenerated();
        assertTrue(generator.hasGeneratedPositions);
        assertTrue(generator.calls == 1);
    }

    static final class RecordingGenerator {
        boolean hasGeneratedPositions;
        boolean failNext;
        int calls;

        void generatePositions() {
            calls++;
            if (failNext) {
                throw new IllegalStateException("companion cache unavailable");
            }
        }

        void ensureStructuresGenerated() {
            network.vonix.threadedhorizons.common.worldgen.StructureGeneration.ensureGenerated(
                    this,
                    () -> hasGeneratedPositions,
                    value -> hasGeneratedPositions = value,
                    this::generatePositions
            );
        }
    }
}
