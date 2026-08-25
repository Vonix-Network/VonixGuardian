package network.vonix.guardian.core.rollback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded in-memory pending preview state for CoreProtect-style apply/cancel.
 *
 * <p>Each request receives a globally monotonic token. The actor-token map is
 * bounded, but tokens remain unique after an actor entry is evicted, so an old
 * worker can never match a future request for that actor.</p>
 */
public final class RollbackPreviewStore {

    public record Snapshot(long generation, RollbackResult result) {}

    private final int maxEntries;
    private final Map<UUID, RollbackResult> pending;
    private final Map<UUID, Long> generations;
    private long nextGeneration;

    public RollbackPreviewStore(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.pending = new LinkedHashMap<>(maxEntries, 0.75f, true);
        this.generations = new LinkedHashMap<>(maxEntries, 0.75f, true);
    }

    /** Store a player-owned preview using its actor UUID as the key. */
    public synchronized boolean put(RollbackResult result) {
        return result != null && result.actorUuid() != null && put(result.actorUuid(), result);
    }

    /** Store a preview under an explicit actor key, including the console key. */
    public synchronized boolean put(UUID actorKey, RollbackResult result) {
        if (actorKey == null) {
            return false;
        }
        if (!applicable(result)) {
            pending.remove(actorKey);
            return false;
        }
        store(actorKey, result);
        return true;
    }

    /** Start a new request generation and invalidate any older preview. */
    public synchronized long invalidate(UUID actorKey) {
        if (actorKey == null) {
            return 0L;
        }
        pending.remove(actorKey);
        return advance(actorKey);
    }

    /** Return the current actor generation without changing it. */
    public synchronized long currentGeneration(UUID actorKey) {
        return actorKey == null ? 0L : generations.getOrDefault(actorKey, 0L);
    }

    /** Store only if the request generation is still current. */
    public synchronized boolean putIfGeneration(UUID actorKey, long generation, RollbackResult result) {
        if (actorKey == null || generation != currentGeneration(actorKey) || !applicable(result)) {
            return false;
        }
        store(actorKey, result);
        return true;
    }

    /** Inspect a pending preview without consuming it. */
    public synchronized Optional<RollbackResult> peek(UUID actorKey) {
        return Optional.ofNullable(pending.get(actorKey));
    }

    /** Read the exact pending preview and its generation atomically. */
    public synchronized Optional<Snapshot> snapshot(UUID actorKey) {
        if (actorKey == null) {
            return Optional.empty();
        }
        RollbackResult result = pending.get(actorKey);
        return result == null
                ? Optional.empty()
                : Optional.of(new Snapshot(generations.getOrDefault(actorKey, 0L), result));
    }

    /** Consume a pending preview for legacy apply or cancel callers. */
    public synchronized Optional<RollbackResult> take(UUID actorKey) {
        RollbackResult result = pending.remove(actorKey);
        if (result != null) {
            advance(actorKey);
        }
        return Optional.ofNullable(result);
    }

    /** Consume only when the pending preview is still the exact preview observed by the caller. */
    public synchronized Optional<RollbackResult> takeIfSame(UUID actorKey, RollbackResult expected) {
        return takeIfSame(actorKey, currentGeneration(actorKey), expected);
    }

    /** Consume only when generation and exact preview both still match. */
    public synchronized Optional<RollbackResult> takeIfSame(UUID actorKey, long generation,
                                                              RollbackResult expected) {
        if (actorKey == null || expected == null || generation != currentGeneration(actorKey)) {
            return Optional.empty();
        }
        RollbackResult current = pending.get(actorKey);
        if (current == null || !current.equals(expected)) {
            return Optional.empty();
        }
        pending.remove(actorKey);
        advance(actorKey);
        return Optional.of(current);
    }

    /** Consume and discard a pending preview, fencing in-flight workers. */
    public synchronized boolean cancel(UUID actorKey) {
        boolean existed = actorKey != null && pending.containsKey(actorKey);
        invalidate(actorKey);
        return existed;
    }

    /** Remove all previews and bounded actor tokens; the global token never rewinds. */
    public synchronized void clear() {
        pending.clear();
        generations.clear();
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized int generationEntryCount() {
        return generations.size();
    }

    private void store(UUID actorKey, RollbackResult result) {
        pending.put(actorKey, result);
        while (pending.size() > maxEntries) {
            UUID eldest = pending.keySet().iterator().next();
            pending.remove(eldest);
        }
    }

    private long advance(UUID actorKey) {
        long token = ++nextGeneration;
        generations.put(actorKey, token);
        while (generations.size() > maxEntries) {
            UUID eldest = generations.keySet().iterator().next();
            generations.remove(eldest);
        }
        return token;
    }

    private static boolean applicable(RollbackResult result) {
        return result != null
                && result.preview()
                && result.originalFilter() != null
                && !result.affectedIds().isEmpty()
                && result.affectedIds().stream().allMatch(id -> id != null && id > 0);
    }
}
