package network.vonix.guardian.core.command;

import network.vonix.guardian.core.rollback.UndoStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-actor generation for mutation-command chat. A stale worker must not print
 * an older error after a newer success for the same actor.
 */
public final class CommandChatGuard {

    private static final ConcurrentHashMap<UUID, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private CommandChatGuard() {}

    public static long next(UUID actor) {
        UUID key = actor == null ? UndoStack.CONSOLE_KEY : actor;
        return GENERATIONS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public static boolean isCurrent(UUID actor, long generation) {
        UUID key = actor == null ? UndoStack.CONSOLE_KEY : actor;
        AtomicLong current = GENERATIONS.get(key);
        return current != null && current.get() == generation;
    }
}
