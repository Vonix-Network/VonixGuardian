package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Default {@link AsyncWriteQueue} implementation: bounded {@link ArrayBlockingQueue} drained
 * by a single daemon worker thread. The worker batches up to {@code batchSize} items, or
 * flushes early when the poll interval elapses with anything pending.
 *
 * <p>On sink failure the worker retries the batch up to {@value #MAX_SINK_RETRIES} times with
 * a {@value #RETRY_BACKOFF_MS} ms backoff; if all retries fail the batch is dropped and
 * {@link #permanentlyDropped()} is incremented.
 *
 * <p>All log statements carry the {@code VONIXGUARDIAN_QUEUE} SLF4J marker so server admins
 * can filter queue-internal noise.
 */
public final class BatchedAsyncWriteQueue implements AsyncWriteQueue, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BatchedAsyncWriteQueue.class);
    private static final Marker MARKER = MarkerFactory.getMarker("VONIXGUARDIAN_QUEUE");

    static final int MAX_SINK_RETRIES = 3;
    static final long RETRY_BACKOFF_MS = 250L;
    private static final long DROP_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);

    private final ArrayBlockingQueue<Action> queue;
    private final long flushIntervalMs;
    private final int batchSize;
    private final BatchSink sink;
    private final Thread worker;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong permanentlyDropped = new AtomicLong();
    private final AtomicLong lastDropLogNs = new AtomicLong(Long.MIN_VALUE);

    // v1.1.3-diag: per-type producer + drop counters. Records EVERY submit(),
    // regardless of whether the queue accepted or dropped it, so we can see the
    // true producer-side histogram even when the drainer can't keep up.
    // Type key = action.type().name() (e.g. "BLOCK_PLACE", "ENTITY_SPAWN").
    private final ConcurrentHashMap<String, LongAdder> submittedByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> droppedByType = new ConcurrentHashMap<>();
    private final AtomicLong lastHistogramLogNs = new AtomicLong(Long.MIN_VALUE);
    private static final long HISTOGRAM_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(30);

    // ---- v1.3.0 W4: sliding-window per-type submit-rate meter (30s window) ----
    // For each ActionType we keep a small ring of bucketed counts. A "bucket" is a 1-second
    // slice of the last 30 seconds. On submit, the current bucket (based on nanoTime) is
    // incremented; on read, we sum the last 30 buckets and divide by the window in seconds
    // to get an events/sec rate. Buckets older than the window are lazily zeroed on write
    // and skipped on read.
    //
    // Allocation-rate meter: also tracks the overall allocation (submit) count per second
    // as an aggregate signal — see allocationRatePerSecond().
    //
    // Design notes:
    //   * per-type buckets are AtomicLongArray to keep the write path allocation-free after
    //     the first submit for a given type;
    //   * window/bucket sizing is a compile-time constant to keep read+write O(RATE_BUCKETS);
    //   * no time-source injection — nanoTime() is used directly. Tests that want deterministic
    //     rates use the {@link #resetRateMeterForTest} + fixed-Clock accessor below.
    static final int RATE_WINDOW_SECONDS = 30;
    static final int RATE_BUCKETS = RATE_WINDOW_SECONDS; // 1 bucket per second
    private final ConcurrentHashMap<String, RateBuckets> submitRateByType = new ConcurrentHashMap<>();
    private final RateBuckets aggregateRate = new RateBuckets();

    private volatile boolean shutdown = false;
    private volatile boolean closed = false;
    private volatile boolean paused = false;

    /**
     * @param maxSize         capacity of the underlying ring buffer; must be &gt; 0
     * @param flushIntervalMs worker poll timeout; pending items are flushed when this elapses
     * @param batchSize       max records flushed in a single sink call; must be &gt; 0
     * @param sink            downstream receiver
     * @param tf              thread factory used to spawn the single worker (daemon recommended)
     */
    public BatchedAsyncWriteQueue(int maxSize, long flushIntervalMs, int batchSize,
                                  BatchSink sink, ThreadFactory tf) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0");
        }
        if (flushIntervalMs <= 0) {
            throw new IllegalArgumentException("flushIntervalMs must be > 0");
        }
        this.queue = new ArrayBlockingQueue<>(maxSize);
        this.flushIntervalMs = flushIntervalMs;
        this.batchSize = batchSize;
        this.sink = Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(tf, "tf");
        this.worker = tf.newThread(this::runWorker);
        if (this.worker == null) {
            throw new IllegalStateException("ThreadFactory returned null");
        }
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void submit(Action a) {
        Objects.requireNonNull(a, "action");
        if (shutdown) {
            // After shutdown signal we still try a best-effort offer so a racing producer
            // doesn't silently lose data, but we never block.
        }
        // v1.1.3-diag: count every submit at the producer moment (before the queue
        // decides to accept or drop). This is the TRUE producer rate.
        String typeKey = a.type() == null ? "UNKNOWN" : a.type().name();
        submittedByType.computeIfAbsent(typeKey, k -> new LongAdder()).increment();
        // v1.3.0 W4: sliding-window rate meter — per-type + aggregate allocation rate.
        long nowNs = System.nanoTime();
        submitRateByType.computeIfAbsent(typeKey, k -> new RateBuckets()).tick(nowNs);
        aggregateRate.tick(nowNs);
        if (!queue.offer(a)) {
            long total = dropped.incrementAndGet();
            droppedByType.computeIfAbsent(typeKey, k -> new LongAdder()).increment();
            maybeLogDrop(total);
        }
        maybeLogHistogram();
    }

    @Override
    public void drainAndFlush(long timeoutMs) {
        if (closed) {
            return;
        }
        shutdown = true;
        worker.interrupt();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn(MARKER, "Interrupted while waiting for worker to drain", ie);
        }

        // Force-flush whatever the worker didn't get to (either it timed out or it died).
        if (!queue.isEmpty()) {
            List<Action> remainder = new ArrayList<>(queue.size());
            queue.drainTo(remainder);
            if (!remainder.isEmpty()) {
                flushWithRetry(remainder);
            }
        }

        if (worker.isAlive()) {
            LOG.warn(MARKER, "Worker thread still alive after drainAndFlush({} ms); abandoning",
                    timeoutMs);
        }
        closed = true;
    }

    @Override
    public int depth() {
        return queue.size();
    }

    @Override
    public long dropped() {
        return dropped.get();
    }

    /** @return total batches lost permanently after exhausting sink retries */
    public long permanentlyDropped() {
        return permanentlyDropped.get();
    }

    /**
     * Immutable snapshot of the per-type submit histogram &mdash; called from
     * {@code /vg status} on demand. Never mutates state; safe from any thread.
     *
     * @return unmodifiable map of {@code ActionType} name &rarr; count submitted
     * @since 1.1.7
     */
    public java.util.Map<String, Long> submittedByTypeSnapshot() {
        java.util.LinkedHashMap<String, Long> snap = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.LongAdder> e : submittedByType.entrySet()) {
            snap.put(e.getKey(), e.getValue().sum());
        }
        return java.util.Collections.unmodifiableMap(snap);
    }

    /**
     * Immutable snapshot of the per-type drop histogram &mdash; the sibling of
     * {@link #submittedByTypeSnapshot()}. Empty when no drops have occurred.
     *
     * @return unmodifiable map of {@code ActionType} name &rarr; count dropped
     * @since 1.1.7
     */
    public java.util.Map<String, Long> droppedByTypeSnapshot() {
        java.util.LinkedHashMap<String, Long> snap = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.LongAdder> e : droppedByType.entrySet()) {
            snap.put(e.getKey(), e.getValue().sum());
        }
        return java.util.Collections.unmodifiableMap(snap);
    }

    /**
     * Snapshot of the per-type submit-rate meter, in <em>events per second</em>, averaged
     * over the trailing {@value #RATE_WINDOW_SECONDS}-second window.
     *
     * <p>The window ends at {@code System.nanoTime()} of the call; older buckets are
     * excluded and treated as zero. A type that hasn't received a submit in the last
     * {@value #RATE_WINDOW_SECONDS} seconds will not appear in the map (its bucket ring
     * has fully aged out and yields 0.0).</p>
     *
     * <p>Precision: 1-second bucket granularity, so bursts under one second are rounded
     * up to at least 1.0/{@value #RATE_WINDOW_SECONDS} events/sec. Sufficient for the
     * {@code /vg status} diagnostic surface.</p>
     *
     * @return unmodifiable map of {@code ActionType} name &rarr; events/second (double)
     * @since 1.3.0
     */
    public java.util.Map<String, Double> submitRateByType() {
        long nowNs = System.nanoTime();
        java.util.LinkedHashMap<String, Double> snap = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, RateBuckets> e : submitRateByType.entrySet()) {
            double rate = e.getValue().eventsPerSecond(nowNs);
            if (rate > 0.0) {
                snap.put(e.getKey(), rate);
            }
        }
        return java.util.Collections.unmodifiableMap(snap);
    }

    /**
     * Overall allocation rate across all action types, in events per second, averaged
     * over the trailing {@value #RATE_WINDOW_SECONDS}-second window.
     *
     * <p>Named "allocation rate" because every {@code submit(Action)} allocates the
     * downstream row buffer + JSON payload; watching this number is the operator's
     * signal for "am I building GC pressure faster than I can flush?" — pair with
     * {@code /vg status} queue depth for a full picture.</p>
     *
     * @return events/second (double, &ge; 0)
     * @since 1.3.0
     */
    public double allocationRatePerSecond() {
        return aggregateRate.eventsPerSecond(System.nanoTime());
    }

    /**
     * Immutable, non-draining snapshot of actions still waiting in the in-memory
     * ring buffer. This is intentionally a best-effort diagnostic/API view: it
     * does not include the worker's currently-held batch and may race with the
     * worker flushing items immediately after the snapshot is taken.
     *
     * @return queued actions in queue iteration order
     * @since 1.2.6
     */
    public List<Action> pendingSnapshot() {
        return List.copyOf(queue);
    }

    @Override
    public void close() {
        drainAndFlush(30_000L);
    }

    @Override
    public void setPaused(boolean p) {
        boolean wasPaused = this.paused;
        this.paused = p;
        // When transitioning into paused state, wake the worker so it exits any
        // in-flight poll() and observes the flag before draining more items.
        // Without this an item submitted immediately after setPaused(true) can
        // still race the worker's already-armed poll() and get pulled out of
        // the ring buffer, breaking the `paused = pipeline frozen` contract
        // pendingSnapshot() / queueLookup() rely on.
        if (p && !wasPaused && !shutdown) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    // ---------------------------------------------------------------- internals

    private void runWorker() {
        final List<Action> batch = new ArrayList<>(batchSize);
        final long flushIntervalNs = TimeUnit.MILLISECONDS.toNanos(flushIntervalMs);
        long lastFlushNs = System.nanoTime();
        while (!shutdown || !queue.isEmpty()) {
            try {
                // Time-budgeted poll: never wait longer than the remaining slice of the
                // current flush window. Guarantees any submitted action lands in the sink
                // within flushIntervalMs even under a steady trickle of arrivals that
                // would otherwise keep poll() returning a non-null head forever and never
                // letting batchSize fill — that was the read-after-write visibility bug
                // (admins ran /vg lookup, saw nothing, restarted to "surface" rows; in
                // reality drainAndFlush was force-flushing what runWorker never had cause
                // to flush). See Kafka producer linger.ms / log4j2 AsyncAppender for
                // prior art.
                long elapsedNs = System.nanoTime() - lastFlushNs;
                long remainingNs = flushIntervalNs - elapsedNs;
                long pollMs = remainingNs > 0
                        ? Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs))
                        : 1L;
                // When paused, don't drain the ring buffer — leaving items in
                // `queue` preserves the diagnostic contract of pendingSnapshot()
                // (so operators using `/vg consumer pause` + queueLookup see the
                // in-flight tail) and matches the "paused = pipeline frozen"
                // intuition. shutdown always overrides paused so drainAndFlush()
                // still completes.
                if (paused && !shutdown) {
                    try { Thread.sleep(pollMs); } catch (InterruptedException ignored) {}
                    if (shutdown) break;
                    continue;
                }
                Action head = queue.poll(pollMs, TimeUnit.MILLISECONDS);
                if (head != null) {
                    batch.add(head);
                    queue.drainTo(batch, batchSize - batch.size());
                }
                boolean windowExpired = (System.nanoTime() - lastFlushNs) >= flushIntervalNs;
                // Flush if we hit batchSize, the flush window expired (time-up), or we're
                // shutting down with leftovers.
                if (!batch.isEmpty() && (batch.size() >= batchSize || windowExpired || shutdown) && (!paused || shutdown)) {
                    flushWithRetry(new ArrayList<>(batch));
                    batch.clear();
                    lastFlushNs = System.nanoTime();
                } else if (batch.isEmpty() && windowExpired) {
                    // Reset the window so an idle queue doesn't perpetually report
                    // "windowExpired" the moment the next action arrives.
                    lastFlushNs = System.nanoTime();
                }
            } catch (InterruptedException ie) {
                // Treated as a shutdown signal; loop condition will re-check `shutdown` and
                // we'll drain any remaining items before exiting.
                if (!batch.isEmpty()) {
                    flushWithRetry(new ArrayList<>(batch));
                    batch.clear();
                    lastFlushNs = System.nanoTime();
                }
                if (shutdown) {
                    break;
                }
                // Spurious interrupt while still running — restore flag and continue.
                Thread.currentThread().interrupt();
            } catch (RuntimeException re) {
                LOG.error(MARKER, "Unexpected error in queue worker; continuing", re);
            }
        }
        // Final drain on graceful exit.
        if (!queue.isEmpty()) {
            List<Action> tail = new ArrayList<>(queue.size());
            queue.drainTo(tail);
            if (!tail.isEmpty()) {
                flushWithRetry(tail);
            }
        }
    }

    private void flushWithRetry(List<Action> batch) {
        List<Action> view = Collections.unmodifiableList(batch);
        for (int attempt = 1; attempt <= MAX_SINK_RETRIES; attempt++) {
            try {
                sink.flush(view);
                return;
            } catch (Exception ex) {
                LOG.warn(MARKER, "Batch sink failed on attempt {}/{} (batch size={}): {}",
                        attempt, MAX_SINK_RETRIES, batch.size(), ex.toString());
                if (attempt == MAX_SINK_RETRIES) {
                    break;
                }
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.warn(MARKER, "Interrupted during retry backoff; dropping batch of {}",
                            batch.size());
                    permanentlyDropped.incrementAndGet();
                    return;
                }
            }
        }
        permanentlyDropped.incrementAndGet();
        LOG.warn(MARKER, "Permanently dropped batch of {} after {} failed attempts",
                batch.size(), MAX_SINK_RETRIES);
    }

    private void maybeLogDrop(long totalDropped) {
        long now = System.nanoTime();
        long last = lastDropLogNs.get();
        if (last == Long.MIN_VALUE || now - last >= DROP_LOG_INTERVAL_NS) {
            if (lastDropLogNs.compareAndSet(last, now)) {
                LOG.warn(MARKER,
                        "AsyncWriteQueue full — dropping actions (total dropped so far: {})",
                        totalDropped);
            }
        }
    }

    /**
     * v1.1.7: emit a per-type histogram at most once per {@value #HISTOGRAM_LOG_INTERVAL_NS} ns
     * (30s), and ONLY when there are actionable signals to report — droppedTotal &gt; 0
     * OR queueDepth is materially non-empty. Zero-drops steady-state is silent
     * (fixes the WARN-flood operator complaint in v1.1.5/v1.1.6 where the
     * histogram fired every 30s regardless of drops).
     *
     * <p>Called from every submit() — gated by a nanoTime + CAS so contention is one
     * atomic read per submit, one atomic write per 30s.</p>
     *
     * <p>Format: {@code [DIAG histogram t=30s] submitted: TYPE=N, ...  |  dropped: TYPE=N, ...}
     */
    private void maybeLogHistogram() {
        long now = System.nanoTime();
        long last = lastHistogramLogNs.get();
        if (last != Long.MIN_VALUE && now - last < HISTOGRAM_LOG_INTERVAL_NS) {
            return;
        }
        // v1.1.7: gate emission on actionable signal. Nothing dropped AND queue not
        // backed up = silent. Operators can force verbose mode by dropping the log
        // level for network.vonix.guardian.core.queue to DEBUG.
        long droppedTotal = dropped.get();
        int queueDepth = queue.size();
        int capacity = queueDepth + queue.remainingCapacity();
        boolean actionable = droppedTotal > 0 || queueDepth > (capacity / 4);
        if (!actionable && !LOG.isDebugEnabled()) {
            // Still advance the CAS so the next tick's 30s window starts now.
            lastHistogramLogNs.compareAndSet(last, now);
            return;
        }
        if (!lastHistogramLogNs.compareAndSet(last, now)) {
            return; // another thread beat us to it
        }
        // Snapshot both maps into a rendered string. This runs at most once per
        // 30s so allocation cost is negligible.
        StringBuilder sub = new StringBuilder();
        List<Map.Entry<String, Long>> subSorted = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : submittedByType.entrySet()) {
            subSorted.add(Map.entry(e.getKey(), e.getValue().sum()));
        }
        subSorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < subSorted.size(); i++) {
            if (i > 0) sub.append(", ");
            sub.append(subSorted.get(i).getKey()).append('=').append(subSorted.get(i).getValue());
        }

        StringBuilder drop = new StringBuilder();
        List<Map.Entry<String, Long>> dropSorted = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : droppedByType.entrySet()) {
            dropSorted.add(Map.entry(e.getKey(), e.getValue().sum()));
        }
        dropSorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < dropSorted.size(); i++) {
            if (i > 0) drop.append(", ");
            drop.append(dropSorted.get(i).getKey()).append('=').append(dropSorted.get(i).getValue());
        }
        if (drop.length() == 0) drop.append("(none)");

        // Level chosen by actionability: WARN on drops (operators need to see),
        // DEBUG in verbose steady-state (log-level gated above).
        if (droppedTotal > 0) {
            LOG.warn(MARKER,
                    "[DIAG histogram t=30s] queueDepth={} submittedTotal={} droppedTotal={} | submitted-by-type: {} | dropped-by-type: {}",
                    queueDepth, sumAll(submittedByType), droppedTotal, sub, drop);
        } else {
            LOG.debug(MARKER,
                    "[DIAG histogram t=30s] queueDepth={} submittedTotal={} droppedTotal={} | submitted-by-type: {} | dropped-by-type: {}",
                    queueDepth, sumAll(submittedByType), droppedTotal, sub, drop);
        }
    }

    private static long sumAll(ConcurrentHashMap<String, LongAdder> map) {
        long total = 0;
        for (LongAdder a : map.values()) total += a.sum();
        return total;
    }

    // ================================================================
    // v1.3.0 W4: sliding-window rate meter
    // ================================================================

    /**
     * Fixed-size ring of per-second counter buckets used to compute a sliding-window
     * events/second rate. Buckets are indexed by {@code (secondSinceEpoch % RATE_BUCKETS)};
     * each bucket carries the timestamp of the second it represents so stale buckets
     * (older than the ring size) can be lazily reset on write and skipped on read.
     *
     * <p>Thread-safety: each bucket count is a {@link LongAdder} for contention-free
     * increment; the bucket-timestamp array is an {@link java.util.concurrent.atomic.AtomicLongArray}
     * to allow a racing writer to CAS a fresh timestamp when a bucket wraps. Racing writers
     * against a stale timestamp deterministically resolve on the CAS — one writer resets the
     * bucket, others see the fresh timestamp and just increment. The read path is best-effort
     * (a bucket that flips its timestamp mid-read yields at most 1 second of skew, which is
     * inside the ±1s bucket granularity anyway).</p>
     */
    static final class RateBuckets {
        private final java.util.concurrent.atomic.AtomicLongArray bucketTimestampSec =
            new java.util.concurrent.atomic.AtomicLongArray(RATE_BUCKETS);
        private final LongAdder[] bucketCounts = new LongAdder[RATE_BUCKETS];

        RateBuckets() {
            for (int i = 0; i < RATE_BUCKETS; i++) {
                bucketCounts[i] = new LongAdder();
                bucketTimestampSec.set(i, Long.MIN_VALUE);
            }
        }

        /** Record one event at wall-clock time {@code nowNs} (nanoTime origin). */
        void tick(long nowNs) {
            long sec = TimeUnit.NANOSECONDS.toSeconds(nowNs);
            int idx = (int) Math.floorMod(sec, RATE_BUCKETS);
            long stored = bucketTimestampSec.get(idx);
            if (stored != sec) {
                // Bucket is either stale (previous window) or freshly initialised.
                // CAS to reset the count under the new timestamp.
                if (bucketTimestampSec.compareAndSet(idx, stored, sec)) {
                    bucketCounts[idx].reset();
                }
                // If the CAS lost, another thread already reset it — either way we now
                // increment; if timestamp still doesn't match this second, we're in a
                // deep-contention corner and accept the ±1s skew.
            }
            bucketCounts[idx].increment();
        }

        /** @return events/second over the last {@link #RATE_WINDOW_SECONDS} at nowNs. */
        double eventsPerSecond(long nowNs) {
            long nowSec = TimeUnit.NANOSECONDS.toSeconds(nowNs);
            long minSec = nowSec - (RATE_WINDOW_SECONDS - 1);
            long total = 0L;
            for (int i = 0; i < RATE_BUCKETS; i++) {
                long ts = bucketTimestampSec.get(i);
                if (ts >= minSec && ts <= nowSec) {
                    total += bucketCounts[i].sum();
                }
            }
            return (double) total / (double) RATE_WINDOW_SECONDS;
        }
    }
}
