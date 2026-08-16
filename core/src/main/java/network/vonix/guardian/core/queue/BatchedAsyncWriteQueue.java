package network.vonix.guardian.core.queue;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.DataTruncation;
import java.sql.SQLDataException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Default {@link AsyncWriteQueue} implementation: bounded {@link ArrayBlockingQueue} drained
 * by a single daemon worker thread. The worker batches up to {@code batchSize} items, or
 * flushes early when the poll interval elapses with anything pending.
 *
 * <p>On sink failure the worker retries the batch up to {@value #MAX_SINK_RETRIES} times with
 * a {@value #RETRY_BACKOFF_MS} ms backoff. If all retries fail with a row-shaped failure, the
 * worker recursively bisects the batch to isolate poison actions while still flushing
 * unaffected actions. Global/transient failures are not bisected, avoiding retry storms when
 * the database is down. {@link #permanentlyDropped()} retains the historical sink-failure
 * counter; production queues also retain failed actions in {@link #quarantined()}
 * until a later recovery attempt succeeds.
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
    private final QuarantineStore quarantineStore;
    private final Runnable terminationListener;
    private final Runnable admissionProbe;
    private final Object admissionLock = new Object();
    private final CountDownLatch workerTerminated = new CountDownLatch(1);
    private final AtomicBoolean terminationNotified = new AtomicBoolean();
    private final Map<Long, RecoveryItem> recovery = new LinkedHashMap<>();
    private final Thread worker;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong permanentlyDropped = new AtomicLong();
    private final AtomicLong recoveredFromQuarantine = new AtomicLong();
    private final AtomicLong quarantineOverflow = new AtomicLong();
    private final AtomicLong quarantineWriteFailures = new AtomicLong();
    private final AtomicBoolean quarantineRetentionLimitReached = new AtomicBoolean();
    private final AtomicLong lastDropLogNs = new AtomicLong(Long.MIN_VALUE);

    // v1.1.3-diag: per-type producer + drop counters. Records EVERY submit(),
    // regardless of whether the queue accepted or dropped it, so we can see the
    // true producer-side histogram even when the drainer can't keep up.
    // Type key = action.type().name() (e.g. "BLOCK_PLACE", "ENTITY_SPAWN").
    //
    // v1.3.0 W2: pre-populated at construction with one LongAdder per known
    // ActionType so the hot path is a plain `map.get(key)` — no
    // computeIfAbsent, no lambda capture, no boxing. An unknown/synthetic key
    // (only possible for an out-of-band Action.type() we don't ship) falls back
    // to computeIfAbsent so we still record it.
    private final ConcurrentHashMap<String, LongAdder> submittedByType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> droppedByType = new ConcurrentHashMap<>();
    /** Sentinel bucket key for a null / unknown {@link ActionType} on submit. */
    static final String UNKNOWN_TYPE_KEY = "UNKNOWN";

    /**
     * Package-visible test accessor: internal per-type submit counter map.
     * Regression tests (v1.3.0 W2 {@code BatchedAsyncWriteQueueNoComputeIfAbsentTest})
     * assert this is fully pre-populated at boot so hot-path submit is a plain
     * {@code get()}.
     */
    ConcurrentHashMap<String, LongAdder> submittedByTypeInternal() { return submittedByType; }

    /** Package-visible test accessor for {@link #droppedByType}. */
    ConcurrentHashMap<String, LongAdder> droppedByTypeInternal()   { return droppedByType; }

    /** Package-visible test accessor for {@link #submitRateByType}. */
    ConcurrentHashMap<String, RateBuckets> submitRateByTypeInternal() { return submitRateByType; }
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
        this(maxSize, flushIntervalMs, batchSize, sink, tf, (QuarantineStore) null, null);
    }

    /** Production constructor with a durable local quarantine journal. */
    public BatchedAsyncWriteQueue(int maxSize, long flushIntervalMs, int batchSize,
                                  BatchSink sink, ThreadFactory tf, Path quarantinePath) {
        this(maxSize, flushIntervalMs, batchSize, sink, tf, openQuarantine(quarantinePath), null);
    }

    /**
     * Production constructor with a callback that runs only after the worker
     * has finished its final sink call. Resource owners use this to defer DAO
     * and log closure when a sink cannot be interrupted within the shutdown
     * budget; closing those dependencies while the worker is still flushing
     * would race an in-flight write.
     */
    public BatchedAsyncWriteQueue(int maxSize, long flushIntervalMs, int batchSize,
                                  BatchSink sink, ThreadFactory tf, Path quarantinePath,
                                  Runnable terminationListener) {
        this(maxSize, flushIntervalMs, batchSize, sink, tf,
                openQuarantine(quarantinePath), terminationListener);
    }

    /**
     * Package-visible constructor for tests that need non-default quarantine
     * retention limits (entry/byte caps). Production always uses
     * {@link #BatchedAsyncWriteQueue(int, long, int, BatchSink, ThreadFactory, Path)}.
     */
    BatchedAsyncWriteQueue(int maxSize, long flushIntervalMs, int batchSize,
                           BatchSink sink, ThreadFactory tf, QuarantineStore quarantineStore) {
        this(maxSize, flushIntervalMs, batchSize, sink, tf, quarantineStore, null);
    }

    BatchedAsyncWriteQueue(int maxSize, long flushIntervalMs, int batchSize,
                           BatchSink sink, ThreadFactory tf, QuarantineStore quarantineStore,
                           Runnable terminationListener) {
        this(maxSize, flushIntervalMs, batchSize, sink, tf, quarantineStore,
                terminationListener, null);
    }

    BatchedAsyncWriteQueue(int maxSize, long flushIntervalMs, int batchSize,
                           BatchSink sink, ThreadFactory tf, QuarantineStore quarantineStore,
                           Runnable terminationListener, Runnable admissionProbe) {
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
        this.quarantineStore = quarantineStore;
        this.terminationListener = terminationListener == null ? () -> { } : terminationListener;
        this.admissionProbe = admissionProbe;
        if (quarantineStore != null) {
            long firstRetry = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
            for (QuarantineStore.Entry entry : quarantineStore.entries()) {
                // Durable SINK_SUCCEEDED means the sink already accepted this
                // row; restart must retry only journal ACK, never re-flush.
                // Loaded marker sets both sinkSucceeded and markerDurable.
                boolean durable = entry.sinkSucceeded();
                recovery.put(entry.sequence(), new RecoveryItem(entry.sequence(), entry.action(), 0,
                        firstRetry, durable, durable));
            }
        }
        Objects.requireNonNull(tf, "tf");
        // v1.3.0 W2: pre-populate per-type maps at boot so hot-path submit is
        // a plain map.get() with no computeIfAbsent, no lambda capture, no
        // hidden allocation. Every ActionType.values() entry + the UNKNOWN
        // sentinel gets its own LongAdder + RateBuckets. Steady-state memory
        // cost: ActionType.values().length * (LongAdder + LongAdder + RateBuckets)
        // ~= 40 * ~1KB = ~40KB, one-time at boot.
        for (ActionType t : ActionType.values()) {
            submittedByType.put(t.name(), new LongAdder());
            droppedByType.put(t.name(), new LongAdder());
            submitRateByType.put(t.name(), new RateBuckets());
        }
        submittedByType.put(UNKNOWN_TYPE_KEY, new LongAdder());
        droppedByType.put(UNKNOWN_TYPE_KEY, new LongAdder());
        submitRateByType.put(UNKNOWN_TYPE_KEY, new RateBuckets());
        this.worker = tf.newThread(this::runWorker);
        if (this.worker == null) {
            throw new IllegalStateException("ThreadFactory returned null");
        }
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean submit(Action a) {
        Objects.requireNonNull(a, "action");
        if (shutdown) {
            // After shutdown signal we still try a best-effort offer so a racing producer
            // doesn't silently lose data, but we never block.
        }
        // v1.1.3-diag: count every submit at the producer moment (before the queue
        // decides to accept or drop). This is the TRUE producer rate.
        //
        // v1.3.0 W2: pre-populated maps mean the hot path is a plain get(). We
        // still fall back to computeIfAbsent for a truly out-of-band ActionType
        // (impossible for shipped code — every submit path uses ActionType enum
        // values) so ad-hoc synthetic actions in tests / future extensions still
        // land in the histogram.
        String typeKey = a.type() == null ? UNKNOWN_TYPE_KEY : a.type().name();
        LongAdder submittedCounter = submittedByType.get(typeKey);
        if (submittedCounter == null) {
            submittedCounter = submittedByType.computeIfAbsent(typeKey, k -> new LongAdder());
        }
        submittedCounter.increment();
        // v1.3.0 W4: sliding-window rate meter — per-type + aggregate allocation rate.
        long nowNs = System.nanoTime();
        RateBuckets rateBuckets = submitRateByType.get(typeKey);
        if (rateBuckets == null) {
            rateBuckets = submitRateByType.computeIfAbsent(typeKey, k -> new RateBuckets());
        }
        rateBuckets.tick(nowNs);
        aggregateRate.tick(nowNs);
        boolean accepted;
        synchronized (admissionLock) {
            if (shutdown || closed) {
                accepted = false;
            } else {
                // Test-only probe runs inside the same admission boundary as
                // offer(), allowing a deterministic late-producer regression.
                if (admissionProbe != null) {
                    admissionProbe.run();
                }
                accepted = queue.offer(a);
            }
        }
        if (!accepted) {
            long total = dropped.incrementAndGet();
            LongAdder droppedCounter = droppedByType.get(typeKey);
            if (droppedCounter == null) {
                droppedCounter = droppedByType.computeIfAbsent(typeKey, k -> new LongAdder());
            }
            droppedCounter.increment();
            maybeLogDrop(total);
            maybeLogHistogram();
            return false;
        }
        maybeLogHistogram();
        return true;
    }

    @Override
    public void drainAndFlush(long timeoutMs) {
        synchronized (admissionLock) {
            if (closed) {
                return;
            }
            // Admission and the state transition are one critical section:
            // no producer can pass the check and enqueue after shutdown has
            // begun. The worker owns the final sink drain before its listener.
            shutdown = true;
        }
        worker.interrupt();
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
            if (remaining > 0) {
                worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warn(MARKER, "Interrupted while waiting for worker to drain", ie);
        }

        if (worker.isAlive()) {
            LOG.warn(MARKER, "Worker thread still alive after drainAndFlush({} ms); abandoning queued tail without concurrent sink writes",
                    timeoutMs);
            List<Action> abandoned = new ArrayList<>(queue.size());
            queue.drainTo(abandoned);
            permanentlyDrop(abandoned, "drain timeout while worker still alive");
            synchronized (admissionLock) {
                closed = true;
            }
            return;
        }

        // The worker's finally block drains its local batch and queue tail
        // before notifying the termination listener. Never call the sink here:
        // the listener may already have closed DAO/log resources.
        if (!queue.isEmpty()) {
            List<Action> abandoned = new ArrayList<>(queue.size());
            queue.drainTo(abandoned);
            permanentlyDrop(abandoned, "worker terminated before final queue drain");
        }
        synchronized (admissionLock) {
            closed = true;
        }
    }

    /**
     * Wait for the worker, including its final sink call and termination
     * listener, to finish. This is intentionally separate from
     * {@link #drainAndFlush(long)} so a caller can distinguish a timed-out
     * worker from a fully owned shutdown.
     */
    public boolean awaitWorkerTermination(long timeoutMs) {
        try {
            return workerTerminated.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** @return true after the worker and its termination listener have completed. */
    public boolean isWorkerTerminated() {
        return workerTerminated.getCount() == 0L;
    }

    @Override
    public int depth() {
        return queue.size();
    }

    @Override
    public long dropped() {
        return dropped.get();
    }

    /**
     * @return total actions whose normal sink retries were exhausted. With a
     * durable quarantine configured, this is a failed counter, not a loss count.
     */
    public long permanentlyDropped() {
        return permanentlyDropped.get();
    }

    /** Number of actions currently retained in durable quarantine. */
    public long quarantined() {
        synchronized (recovery) { return recovery.size(); }
    }

    /** Number of quarantined actions successfully recovered by this queue instance. */
    public long recoveredFromQuarantine() {
        return recoveredFromQuarantine.get();
    }

    /** Number of actions rejected because the bounded quarantine was full. */
    public long quarantineOverflow() {
        return quarantineOverflow.get();
    }

    /** Number of quarantine writes that failed for an I/O or serialization reason. */
    public long quarantineWriteFailures() {
        return quarantineWriteFailures.get();
    }

    /** Whether the configured bounded quarantine has rejected a row at its retention limit. */
    public boolean quarantineRetentionLimitReached() {
        return quarantineRetentionLimitReached.get();
    }

    /** Whether this queue has a durable quarantine configured. */
    public boolean quarantineEnabled() {
        return quarantineStore != null;
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
            // v1.3.0 W2: filter zeros so pre-populated but-never-touched buckets
            // don't leak into /vg status (preserves pre-1.3 snapshot semantics).
            long v = e.getValue().sum();
            if (v > 0L) snap.put(e.getKey(), v);
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
            // v1.3.0 W2: filter zeros (see submittedByTypeSnapshot).
            long v = e.getValue().sum();
            if (v > 0L) snap.put(e.getKey(), v);
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
        try {
            while (!shutdown || !queue.isEmpty()) {
                try {
                if (!shutdown && processDueRecovery()) {
                    lastFlushNs = System.nanoTime();
                    continue;
                }
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
                if (!shutdown) {
                    long recoveryWait = recoveryWaitMillis();
                    if (recoveryWait >= 0L) pollMs = Math.min(pollMs, Math.max(1L, recoveryWait));
                }
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
                if (head == null && !shutdown) processDueRecovery();
                boolean windowExpired = (System.nanoTime() - lastFlushNs) >= flushIntervalNs;
                // Flush if we hit batchSize, the flush window expired (time-up), or we're
                // shutting down with leftovers.
                if (!batch.isEmpty() && (batch.size() >= batchSize || windowExpired || shutdown) && (!paused || shutdown)) {
                    // drainAndFlush/close interrupt the worker only to wake poll/sleep.
                    // Consume that wake signal before retry backoff so global sink
                    // failures still receive the full MAX_SINK_RETRIES budget.
                    if (shutdown) {
                        Thread.interrupted();
                    }
                    flushWithRetry(new ArrayList<>(batch));
                    batch.clear();
                    lastFlushNs = System.nanoTime();
                } else if (batch.isEmpty() && windowExpired) {
                    // Reset the window so an idle queue doesn't perpetually report
                    // "windowExpired" the moment the next action arrives.
                    lastFlushNs = System.nanoTime();
                }
                } catch (InterruptedException ie) {
                // The finally block owns local-batch cleanup. Keeping this
                // batch intact also preserves pause semantics until shutdown.
                if (shutdown) {
                    break;
                }
                // setPaused(true) interrupts the worker to break it out of an
                // armed poll. Consume the interrupt so the next iteration can
                // observe the paused guard without polling another ring item.
                if (paused) {
                    continue;
                }
                // Worker-internal interrupts are control signals, not a caller
                // cancellation contract. Leaving the flag cleared prevents a
                // tight loop of immediately interrupted polls.
                continue;
                } catch (RuntimeException re) {
                    LOG.error(MARKER, "Unexpected error in queue worker; continuing", re);
                }
            }
        } finally {
            // Publish the admission boundary before any final drain. This also covers
            // unexpected Error exits: no producer may enqueue between the last queue
            // drain and the termination callback that closes dependent resources.
            synchronized (admissionLock) {
                shutdown = true;
            }
            try {
                flushWorkerRemainder(batch);
            } finally {
                notifyWorkerTerminated();
            }
        }
    }

    private void notifyWorkerTerminated() {
        if (!terminationNotified.compareAndSet(false, true)) {
            return;
        }
        synchronized (admissionLock) {
            shutdown = true;
        }
        try {
            terminationListener.run();
        } catch (Throwable t) {
            LOG.error(MARKER, "Queue termination listener failed", t);
        } finally {
            workerTerminated.countDown();
        }
    }

    private void flushWorkerRemainder(List<Action> batch) {
        // The loop condition only sees the ring buffer. A shutdown can arrive
        // while actions are held in the worker's local batch.
        //
        // Shutdown/pause interrupts are wake signals only. Clear any stale
        // interrupt before the final drain so retry backoff is not spuriously
        // aborted after the first failed sink attempt (global DB-down path).
        Thread.interrupted();
        if (!batch.isEmpty()) {
            try {
                flushWithRetry(new ArrayList<>(batch));
            } finally {
                batch.clear();
            }
        }
        if (!queue.isEmpty()) {
            List<Action> tail = new ArrayList<>(queue.size());
            queue.drainTo(tail);
            if (!tail.isEmpty()) {
                flushWithRetry(tail);
            }
        }
    }

    private void flushWithRetry(List<Action> batch) {
        flushWithRetry(batch, Long.MAX_VALUE);
    }

    private void flushWithRetry(List<Action> batch, long deadlineNs) {
        if (batch.isEmpty()) {
            return;
        }
        if (deadlineExpired(deadlineNs)) {
            permanentlyDrop(batch, "flush deadline expired before retry");
            return;
        }
        Exception failure = tryFlushBatchWithRetry(batch, deadlineNs);
        if (failure == null) {
            return;
        }
        if (!isLikelyRowSpecificFailure(failure)) {
            permanentlyDrop(batch, "non-row-specific sink failure after retries: " + failure);
            return;
        }
        isolateAndFlushFailedBatch(batch, deadlineNs);
    }

    /**
     * Try the normal whole-batch retry path first. Returns {@code null} when
     * the batch reached the sink or was retained by the quarantine path because
     * the caller's deadline/backoff was interrupted. A non-null exception means
     * all normal retry attempts failed and the caller can decide whether
     * poison-row isolation is appropriate.
     */
    private Exception tryFlushBatchWithRetry(List<Action> batch, long deadlineNs) {
        List<Action> view = Collections.unmodifiableList(batch);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_SINK_RETRIES; attempt++) {
            if (deadlineExpired(deadlineNs)) {
                permanentlyDrop(batch, "flush deadline expired during retry");
                return null;
            }
            try {
                sink.flush(view);
                return null;
            } catch (Exception ex) {
                last = ex;
                LOG.warn(MARKER, "Batch sink failed on attempt {}/{} (batch size={}): {}",
                        attempt, MAX_SINK_RETRIES, batch.size(), ex.toString());
                if (attempt == MAX_SINK_RETRIES) {
                    break;
                }
                try {
                    Thread.sleep(backoffMillis(deadlineNs));
                } catch (InterruptedException ie) {
                    // drainAndFlush/close wake the worker with interrupt. That is a
                    // poll/sleep wake, not cancellation of the unbounded final drain
                    // retry budget. Keep fail-closed interrupt behavior for live
                    // (non-shutdown) flushes and for deadline-bounded caller drains.
                    if (shutdown && deadlineNs == Long.MAX_VALUE) {
                        continue;
                    }
                    Thread.currentThread().interrupt();
                    permanentlyDrop(batch, "interrupted during retry backoff");
                    return null;
                }
            }
        }
        return last;
    }

    private void isolateAndFlushFailedBatch(List<Action> batch, long deadlineNs) {
        if (deadlineExpired(deadlineNs)) {
            permanentlyDrop(batch, "flush deadline expired during poison isolation");
            return;
        }
        if (batch.size() == 1) {
            permanentlyDrop(batch, "isolated poison action after " + MAX_SINK_RETRIES + " failed sink attempts");
            return;
        }

        int mid = batch.size() / 2;
        LOG.warn(MARKER,
                "Batch of {} action(s) failed after {} attempts with a row-specific error; bisecting into {} and {} action(s)",
                batch.size(), MAX_SINK_RETRIES, mid, batch.size() - mid);
        probeOrSplit(new ArrayList<>(batch.subList(0, mid)), deadlineNs);
        probeOrSplit(new ArrayList<>(batch.subList(mid, batch.size())), deadlineNs);
    }

    /** Cheap child probe used only after the root batch already exhausted the normal retry path. */
    private void probeOrSplit(List<Action> batch, long deadlineNs) {
        if (batch.isEmpty()) {
            return;
        }
        if (deadlineExpired(deadlineNs)) {
            permanentlyDrop(batch, "flush deadline expired during poison probe");
            return;
        }
        try {
            sink.flush(Collections.unmodifiableList(batch));
        } catch (Exception ex) {
            if (!isLikelyRowSpecificFailure(ex)) {
                permanentlyDrop(batch, "non-row-specific sink failure during poison probe: " + ex);
                return;
            }
            isolateAndFlushFailedBatch(batch, deadlineNs);
        }
    }

    private boolean deadlineExpired(long deadlineNs) {
        return deadlineNs != Long.MAX_VALUE && System.nanoTime() >= deadlineNs;
    }

    private long backoffMillis(long deadlineNs) {
        if (deadlineNs == Long.MAX_VALUE) {
            return RETRY_BACKOFF_MS;
        }
        long remainingNs = deadlineNs - System.nanoTime();
        if (remainingNs <= 0L) {
            return 1L;
        }
        return Math.max(1L, Math.min(RETRY_BACKOFF_MS, TimeUnit.NANOSECONDS.toMillis(remainingNs)));
    }

    private void permanentlyDrop(List<Action> batch, String reason) {
        if (batch.isEmpty()) {
            return;
        }
        long total = permanentlyDropped.addAndGet(batch.size());
        LOG.warn(MARKER,
                "Quarantining {} action(s) after sink failure: {} (total failed={})",
                batch.size(), reason, total);
        if (quarantineStore == null) return;
        for (Action action : batch) {
            try {
                long sequence = quarantineStore.append(action);
                if (sequence < 0L) {
                    quarantineOverflow.incrementAndGet();
                    quarantineRetentionLimitReached.set(true);
                    LOG.error(MARKER, "Durable quarantine full; action id={} cannot be retained", action.id());
                    continue;
                }
                synchronized (recovery) {
                    recovery.put(sequence, new RecoveryItem(sequence, action, 0,
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(1L), false, false));
                }
            } catch (IOException e) {
                quarantineWriteFailures.incrementAndGet();
                LOG.error(MARKER, "Durable quarantine write failed for action id={}", action.id(), e);
            }
        }
    }

    private boolean processDueRecovery() {
        RecoveryItem item = null;
        long now = System.nanoTime();
        synchronized (recovery) {
            for (RecoveryItem candidate : recovery.values()) {
                if (candidate.nextRetryNs <= now) { item = candidate; break; }
            }
        }
        if (item == null) return false;

        Exception last = null;
        boolean sinkSucceeded = item.sinkSucceeded;
        boolean markerDurable = item.markerDurable;
        if (!sinkSucceeded) {
            for (int attempt = 1; attempt <= MAX_SINK_RETRIES; attempt++) {
                try {
                    sink.flush(List.of(item.action));
                    sinkSucceeded = true;
                    break;
                } catch (Exception failure) {
                    last = failure;
                    if (attempt < MAX_SINK_RETRIES) {
                        try { Thread.sleep(RETRY_BACKOFF_MS); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        if (sinkSucceeded) {
            // Persist durable sink-success before attempting ACK so a crash
            // between sink and ACK never re-flushes on restart.
            // sinkSucceeded (in-memory, skip reflush) is independent of
            // markerDurable (journal SINK_SUCCEEDED persisted). After a
            // marker failure keep sinkSucceeded=true but markerDurable=false
            // and retry the marker on every due pass before ACK.
            if (quarantineStore != null && !markerDurable) {
                try {
                    quarantineStore.markSinkSucceeded(item.sequence);
                    markerDurable = true;
                } catch (Exception markerFailure) {
                    long delaySeconds = Math.min(60L, 1L << Math.min(6, item.attempts + 1));
                    synchronized (recovery) {
                        recovery.put(item.sequence, new RecoveryItem(item.sequence, item.action,
                                item.attempts + 1, System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds),
                                true, false));
                    }
                    LOG.warn(MARKER,
                            "Quarantine sink-success marker deferred for action id={} after successful sink recovery: {}",
                            item.action.id(), markerFailure.toString());
                    return true;
                }
            }
            // ACK only after the durable marker is confirmed. Never re-flush a
            // row that already reached the sink just because journal retirement failed.
            Exception ackFailure = null;
            for (int attempt = 1; attempt <= MAX_SINK_RETRIES; attempt++) {
                try {
                    if (quarantineStore != null) quarantineStore.acknowledge(item.sequence);
                    synchronized (recovery) { recovery.remove(item.sequence); }
                    recoveredFromQuarantine.incrementAndGet();
                    return true;
                } catch (Exception failure) {
                    ackFailure = failure;
                    if (attempt < MAX_SINK_RETRIES) {
                        try { Thread.sleep(RETRY_BACKOFF_MS); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            long delaySeconds = Math.min(60L, 1L << Math.min(6, item.attempts + 1));
            synchronized (recovery) {
                recovery.put(item.sequence, new RecoveryItem(item.sequence, item.action,
                        item.attempts + 1, System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds),
                        true, true));
            }
            LOG.warn(MARKER, "Quarantine ACK deferred for action id={} after successful sink recovery: {}",
                    item.action.id(), ackFailure == null ? "interrupted" : ackFailure.toString());
            return true;
        }

        long delaySeconds = Math.min(60L, 1L << Math.min(6, item.attempts + 1));
        synchronized (recovery) {
            recovery.put(item.sequence, new RecoveryItem(item.sequence, item.action,
                    item.attempts + 1, System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds),
                    false, false));
        }
        LOG.warn(MARKER, "Quarantine recovery deferred for action id={} after retries: {}",
                item.action.id(), last == null ? "interrupted" : last.toString());
        return true;
    }

    private long recoveryWaitMillis() {
        long earliest = Long.MAX_VALUE;
        synchronized (recovery) {
            for (RecoveryItem item : recovery.values()) earliest = Math.min(earliest, item.nextRetryNs);
        }
        if (earliest == Long.MAX_VALUE) return -1L;
        long remaining = earliest - System.nanoTime();
        return remaining <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(remaining);
    }

    /**
     * @param sinkSucceeded  in-memory: recovery sink.flush already accepted this row; never re-flush
     * @param markerDurable  journal SINK_SUCCEEDED is durable; only then may ACK proceed.
     *                       On restart load, both are true when the entry carries SINK_SUCCEEDED.
     */
    private record RecoveryItem(long sequence, Action action, int attempts, long nextRetryNs,
                                boolean sinkSucceeded, boolean markerDurable) {}

    private static boolean isLikelyRowSpecificFailure(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof IllegalArgumentException
                    || cur instanceof SQLDataException
                    || cur instanceof DataTruncation
                    || cur instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private static QuarantineStore openQuarantine(Path quarantinePath) {
        if (quarantinePath == null) return null;
        try {
            return new QuarantineStore(quarantinePath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to open durable queue quarantine", e);
        }
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
            int idx = Math.floorMod(sec, RATE_BUCKETS);
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
