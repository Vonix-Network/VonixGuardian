/**
 * Bounded async write queue + batch flusher.
 *
 * <p>See {@link network.vonix.guardian.core.queue.AsyncWriteQueue} for the public contract and
 * {@link network.vonix.guardian.core.queue.BatchedAsyncWriteQueue} for the production
 * implementation backed by an {@link java.util.concurrent.ArrayBlockingQueue} and a single
 * daemon worker thread.
 */
package network.vonix.guardian.core.queue;
