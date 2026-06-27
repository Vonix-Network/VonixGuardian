package network.vonix.guardian.core.rollback;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-actor LIFO of recent {@link RollbackResult}s, used by the {@code /vg undo}
 * command to revert the actor's most recent rollback or restore.
 *
 * <p>Each actor's stack is bounded to {@link #MAX_PER_ACTOR} entries; older
 * entries are evicted when the cap is exceeded. The console (no actor UUID) is
 * keyed under {@link #CONSOLE_KEY}.</p>
 *
 * <p>Thread-safety: all mutating methods are {@code synchronized}.</p>
 */
public final class UndoStack {

    /** Default per-actor cap. */
    public static final int MAX_PER_ACTOR = 20;

    /** Sentinel UUID used to bucket console-originated operations. */
    public static final UUID CONSOLE_KEY = new UUID(0L, 0L);

    private final int maxPerActor;
    private final Map<UUID, Deque<RollbackResult>> stacks = new HashMap<>();

    /** New stack with the default per-actor cap of {@value #MAX_PER_ACTOR}. */
    public UndoStack() {
        this(MAX_PER_ACTOR);
    }

    /**
     * @param maxPerActor cap on entries per actor; must be {@code >= 1}
     */
    public UndoStack(int maxPerActor) {
        if (maxPerActor < 1) {
            throw new IllegalArgumentException("maxPerActor < 1: " + maxPerActor);
        }
        this.maxPerActor = maxPerActor;
    }

    /**
     * Push a result for the given actor. If the actor's stack is at the cap,
     * the oldest entry is dropped first.
     *
     * @param actor  actor UUID; {@code null} maps to {@link #CONSOLE_KEY}
     * @param result result to retain; must not be {@code null}
     */
    public synchronized void push(UUID actor, RollbackResult result) {
        Objects.requireNonNull(result, "result");
        UUID key = actor == null ? CONSOLE_KEY : actor;
        Deque<RollbackResult> q = stacks.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (q.size() >= maxPerActor) {
            q.pollLast(); // evict oldest
        }
        q.push(result);
    }

    /**
     * Pop the most recent result for an actor.
     *
     * @param actor actor UUID; {@code null} maps to {@link #CONSOLE_KEY}
     * @return most recent result, or empty if the actor has no history
     */
    public synchronized Optional<RollbackResult> pop(UUID actor) {
        UUID key = actor == null ? CONSOLE_KEY : actor;
        Deque<RollbackResult> q = stacks.get(key);
        if (q == null || q.isEmpty()) {
            return Optional.empty();
        }
        RollbackResult r = q.pop();
        if (q.isEmpty()) {
            stacks.remove(key);
        }
        return Optional.of(r);
    }

    /**
     * Peek the most recent result for an actor without removing it.
     *
     * @param actor actor UUID; {@code null} maps to {@link #CONSOLE_KEY}
     * @return most recent result, or empty
     */
    public synchronized Optional<RollbackResult> peek(UUID actor) {
        UUID key = actor == null ? CONSOLE_KEY : actor;
        Deque<RollbackResult> q = stacks.get(key);
        return q == null || q.isEmpty() ? Optional.empty() : Optional.of(q.peek());
    }

    /** Snapshot of an actor's history (newest first). */
    public synchronized List<RollbackResult> history(UUID actor) {
        UUID key = actor == null ? CONSOLE_KEY : actor;
        Deque<RollbackResult> q = stacks.get(key);
        return q == null ? List.of() : List.copyOf(q);
    }

    /** Total entries across all actors. */
    public synchronized int size() {
        int n = 0;
        for (Deque<RollbackResult> q : stacks.values()) {
            n += q.size();
        }
        return n;
    }

    /** Drop the actor's history. */
    public synchronized void clear(UUID actor) {
        UUID key = actor == null ? CONSOLE_KEY : actor;
        stacks.remove(key);
    }

    /** Drop all history. */
    public synchronized void clearAll() {
        stacks.clear();
    }
}
