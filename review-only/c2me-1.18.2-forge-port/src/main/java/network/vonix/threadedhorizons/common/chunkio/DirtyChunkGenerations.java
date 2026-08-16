package network.vonix.threadedhorizons.common.chunkio;

import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Per-chunk mutation generations. Only mutations advance the counter.
 * A save captures the current generation; an older completion must not
 * clear unsaved state or forget a newer overlapping mutation.
 */
public final class DirtyChunkGenerations {

    private static final ConcurrentHashMap<Object, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, Object> LOCKS = new ConcurrentHashMap<>();
    public static final TransitionHooks hooks = new TransitionHooks();

    private DirtyChunkGenerations() {
    }

    public static long markMutated(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        synchronized (lockFor(chunk)) {
            return GENERATIONS.computeIfAbsent(chunk, unused -> new AtomicLong()).incrementAndGet();
        }
    }

    public static long captureForSave(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        synchronized (lockFor(chunk)) {
            return currentUnlocked(chunk);
        }
    }

    public static long current(Object chunk) {
        if (chunk == null) {
            return 0L;
        }
        synchronized (lockFor(chunk)) {
            return currentUnlocked(chunk);
        }
    }

    public static boolean isCurrent(Object chunk, long generation) {
        return current(chunk) == generation;
    }

    public static boolean shouldClearUnsaved(Object chunk, long saveGeneration, boolean durableSuccess) {
        return durableSuccess && isCurrent(chunk, saveGeneration);
    }

    public static void applyStoreOutcome(ChunkAccess chunk, long saveGeneration, Throwable throwable) {
        if (chunk == null) {
            return;
        }
        transitionAfterSave(
                chunk,
                saveGeneration,
                throwable == null,
                () -> chunk.setUnsaved(false),
                chunk::isUnsaved,
                () -> chunk.setUnsaved(true)
        );
    }

    public static boolean applyStoreOutcome(Object chunk, long saveGeneration, boolean durableSuccess, AtomicBoolean unsaved) {
        if (unsaved == null) {
            return transitionAfterSave(chunk, saveGeneration, durableSuccess, null, null, null);
        }
        return transitionAfterSave(
                chunk,
                saveGeneration,
                durableSuccess,
                () -> unsaved.set(false),
                unsaved::get,
                () -> unsaved.set(true)
        );
    }

    public static void forget(Object chunk) {
        if (chunk == null) {
            return;
        }
        synchronized (lockFor(chunk)) {
            GENERATIONS.remove(chunk);
        }
    }

    private static boolean transitionAfterSave(
            Object chunk,
            long saveGeneration,
            boolean durableSuccess,
            Runnable clearUnsaved,
            BooleanSupplier isUnsaved,
            Runnable markUnsaved
    ) {
        if (chunk == null) {
            return false;
        }
        Object lock = lockFor(chunk);
        long observed;
        synchronized (lock) {
            observed = currentUnlocked(chunk);
        }
        hooks.fireAfterValidation(chunk, observed);
        synchronized (lock) {
            if (durableSuccess && currentUnlocked(chunk) == saveGeneration) {
                if (clearUnsaved != null) {
                    clearUnsaved.run();
                }
                GENERATIONS.remove(chunk);
                return true;
            }
            if (!durableSuccess && isUnsaved != null && markUnsaved != null && !isUnsaved.getAsBoolean()) {
                markUnsaved.run();
            }
            return false;
        }
    }

    private static long currentUnlocked(Object chunk) {
        AtomicLong generation = GENERATIONS.get(chunk);
        return generation == null ? 0L : generation.get();
    }

    private static Object lockFor(Object chunk) {
        return LOCKS.computeIfAbsent(chunk, unused -> new Object());
    }

    public static final class TransitionHooks {
        public volatile BiConsumer<Object, Long> afterValidationBeforeClear;

        void fireAfterValidation(Object chunk, long observedGeneration) {
            BiConsumer<Object, Long> hook = this.afterValidationBeforeClear;
            if (hook != null) {
                hook.accept(chunk, observedGeneration);
            }
        }

        public void reset() {
            this.afterValidationBeforeClear = null;
        }
    }
}
