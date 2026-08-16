package network.vonix.threadedhorizons.common.chunkio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class DirtyChunkGenerationsTest {

    @AfterEach
    void resetHooks() {
        DirtyChunkGenerations.hooks.reset();
    }

    @Test
    void saveCapturesMutationGenerationAndDoesNotAdvanceOnRetry() {
        Object chunk = new Object();
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        long firstSave = DirtyChunkGenerations.captureForSave(chunk);
        long secondSave = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(mutation, firstSave);
        assertEquals(mutation, secondSave);
        assertTrue(DirtyChunkGenerations.isCurrent(chunk, firstSave));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void captureForSaveDoesNotCreateOrAdvanceGeneration() {
        Object chunk = new Object();
        assertEquals(0L, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        assertEquals(0L, DirtyChunkGenerations.captureForSave(chunk));
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        assertEquals(1L, mutation);
        assertEquals(mutation, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(mutation, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(mutation, DirtyChunkGenerations.current(chunk));
        DirtyChunkGenerations.forget(chunk);
        assertEquals(0L, DirtyChunkGenerations.captureForSave(chunk));
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
    }

    @Test
    void mutationDuringSerializationKeepsNewerGenerationDirtyForRetry() {
        Object chunk = new Object();
        long firstMutation = DirtyChunkGenerations.markMutated(chunk);
        long saveGeneration = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(firstMutation, saveGeneration);

        long overlappingMutation = DirtyChunkGenerations.markMutated(chunk);
        assertTrue(overlappingMutation > saveGeneration);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, saveGeneration, true),
                "older durable completion must not clear a newer mutation");
        assertFalse(DirtyChunkGenerations.isCurrent(chunk, saveGeneration));
        assertEquals(overlappingMutation, DirtyChunkGenerations.current(chunk));

        long retryGeneration = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(overlappingMutation, retryGeneration);
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, retryGeneration, true));
        DirtyChunkGenerations.forget(chunk);
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, retryGeneration, true));
    }

    @Test
    void failedOlderStoreDoesNotClearNewerMutation() {
        Object chunk = new Object();
        long saveGeneration = DirtyChunkGenerations.captureForSave(chunk);
        DirtyChunkGenerations.markMutated(chunk);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, saveGeneration, false));
        assertTrue(DirtyChunkGenerations.current(chunk) > saveGeneration);
        long retryGeneration = DirtyChunkGenerations.captureForSave(chunk);
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, retryGeneration, true));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void overlappingAutosaveUnloadAndShutdownKeepLatestMutation() {
        Object chunk = new Object();
        long mutation = DirtyChunkGenerations.markMutated(chunk);
        long autosave = DirtyChunkGenerations.captureForSave(chunk);
        DirtyChunkGenerations.markMutated(chunk);
        long unload = DirtyChunkGenerations.captureForSave(chunk);
        DirtyChunkGenerations.markMutated(chunk);
        long shutdown = DirtyChunkGenerations.captureForSave(chunk);
        assertEquals(mutation, autosave);
        assertTrue(unload > autosave);
        assertTrue(shutdown > unload);
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, autosave, true));
        assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, unload, true));
        assertTrue(DirtyChunkGenerations.shouldClearUnsaved(chunk, shutdown, true));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void successfulSaveClearsOnlyWhenGenerationIsStillCurrent() {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long generation = DirtyChunkGenerations.markMutated(chunk);
        assertTrue(DirtyChunkGenerations.applyStoreOutcome(chunk, generation, true, unsaved));
        assertFalse(unsaved.get());
        assertEquals(0L, DirtyChunkGenerations.current(chunk));
        DirtyChunkGenerations.forget(chunk);
    }

    @Test
    void mutationBetweenValidationAndClearKeepsNewerDirtyState() throws Exception {
        assertMutationBetweenValidationAndClearSurvives("direct");
    }

    public static void assertMutationBetweenValidationAndClearSurvives(String profile) throws Exception {
        Object chunk = new Object();
        AtomicBoolean unsaved = new AtomicBoolean(true);
        long saveGeneration = DirtyChunkGenerations.markMutated(chunk);
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch mutated = new CountDownLatch(1);
        AtomicLong newerGeneration = new AtomicLong();
        DirtyChunkGenerations.hooks.afterValidationBeforeClear = (key, observed) -> {
            assertEquals(chunk, key, profile);
            assertEquals(saveGeneration, observed, profile);
            validated.countDown();
            awaitLatch(mutated, profile);
        };
        try {
            CompletableFuture<Boolean> cleared = CompletableFuture.supplyAsync(
                    () -> DirtyChunkGenerations.applyStoreOutcome(chunk, saveGeneration, true, unsaved));
            assertTrue(validated.await(5, TimeUnit.SECONDS), profile + " validation hook");
            long overlapping = DirtyChunkGenerations.markMutated(chunk);
            newerGeneration.set(overlapping);
            unsaved.set(true);
            mutated.countDown();
            assertFalse(cleared.get(5, TimeUnit.SECONDS), profile + " must not clear after overlapping mutation");
            assertTrue(unsaved.get(), profile + " must remain dirty");
            assertEquals(overlapping, DirtyChunkGenerations.current(chunk), profile);
            assertTrue(DirtyChunkGenerations.isCurrent(chunk, overlapping), profile);
            assertFalse(DirtyChunkGenerations.shouldClearUnsaved(chunk, saveGeneration, true), profile);
        } finally {
            DirtyChunkGenerations.hooks.reset();
            DirtyChunkGenerations.forget(chunk);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String profile) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException(profile + " dirty-generation interleave latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrupted);
        }
    }
}
