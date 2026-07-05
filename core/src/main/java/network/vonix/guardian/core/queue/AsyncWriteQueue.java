package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;

/**
 * Bounded, non-blocking async write queue for {@link Action} records. Producers (the server
 * thread, primarily) call {@link #submit(Action)} and return immediately; a worker thread
 * drains the queue and hands batches to a downstream sink (typically the DAO).
 *
 * <p>Contract (SHARED-CONTRACTS § 5): submit is non-blocking and drops with a WARN log if the
 * queue is at its configured {@code maxSize}; {@link #drainAndFlush(long)} is called on server
 * stop and force-flushes any pending batch.
 */
public interface AsyncWriteQueue {

    /**
     * Non-blocking enqueue of an action. Drops the action and increments the {@link #dropped()}
     * counter (with a rate-limited WARN log) if the queue is at its configured maximum size.
     *
     * @return {@code true} when the action was enqueued, {@code false} when it
     *         was dropped because the queue was full
     */
    boolean submit(Action a);

    /**
     * Stop accepting new work, signal the worker, and force-flush any pending items within the
     * given timeout budget. Implementations should treat exhausted budgets as a WARN, not an
     * error.
     *
     * @param timeoutMs maximum milliseconds to wait for the queue to drain
     */
    void drainAndFlush(long timeoutMs);

    /**
     * @return current number of buffered (unflushed) actions; used by {@code /vg status}
     */
    int depth();

    /**
     * @return total number of actions dropped due to backpressure since startup
     */
    long dropped();

    /**
     * Pause or resume the consumer worker. Paused queues continue to accept
     * submissions (which still count toward the bounded {@code maxSize} budget
     * and may be dropped if the queue fills), but the worker thread will not
     * flush batches to the sink until {@code setPaused(false)} is called. Used
     * by {@code /co consumer pause|resume} for maintenance windows. Default
     * implementation is a no-op for backward compatibility.
     *
     * @param paused {@code true} to halt flushes, {@code false} to resume
     */
    default void setPaused(boolean paused) {
        // no-op default
    }

    /** @return {@code true} if the consumer is currently paused. */
    default boolean isPaused() {
        return false;
    }
}
