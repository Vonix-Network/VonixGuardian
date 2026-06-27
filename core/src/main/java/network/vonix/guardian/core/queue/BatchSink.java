package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;

import java.util.List;

/**
 * Terminal receiver for a flushed batch of {@link Action} records. Typically backed by the
 * DAO's {@code insertBatch}, but kept abstract so the queue can be unit-tested without a
 * database and so alternative sinks (e.g., JSON-lines mirror) can be wired in.
 *
 * <p>Implementations are invoked from a single worker thread; they need not be thread-safe.
 * Implementations MAY throw to signal a transient failure — the queue will retry the batch
 * a bounded number of times before dropping it.
 */
@FunctionalInterface
public interface BatchSink {

    /**
     * Flush a batch downstream. The list is owned by the caller and MUST NOT be retained or
     * mutated by the implementation beyond the duration of the call.
     *
     * @param batch non-empty, immutable view of pending actions
     * @throws Exception to signal a transient failure (the queue will retry)
     */
    void flush(List<Action> batch) throws Exception;
}
