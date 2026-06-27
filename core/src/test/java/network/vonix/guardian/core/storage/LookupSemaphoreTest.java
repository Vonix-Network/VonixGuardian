package network.vonix.guardian.core.storage;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.AbstractJdbcDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code AbstractJdbcDao}'s lookup semaphore caps the number of
 * concurrently-executing query operations.
 *
 * <p>A test-only DAO stalls inside {@code borrow()} — which the parent's
 * {@code query()} calls AFTER acquiring the lookup permit but BEFORE releasing
 * it. With permits=2 and 4 racing queries, the observed peak of in-flight
 * {@code borrow()} calls must be exactly 2.
 */
class LookupSemaphoreTest {

    @Test
    void semaphore_caps_concurrent_queries_at_permit_count() throws Exception {
        final int permits = 2;
        final int threads = 4;

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch admittedReached = new CountDownLatch(permits);
        CountDownLatch holdGate = new CountDownLatch(1);

        Semaphore lookupSemaphore = new Semaphore(permits, true);

        StallingDao dao = new StallingDao(lookupSemaphore, inFlight, peak,
            admittedReached, holdGate);
        dao.init();
        // Only start stalling AFTER init() returns — init runs DDL through borrow().
        dao.stall = true;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        dao.query(QueryFilter.empty(), 0, 10);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }));
            }

            // Wait until exactly `permits` callers have made it past the parent's permit
            // acquire (i.e. into borrow()).
            assertThat(admittedReached.await(5, TimeUnit.SECONDS)).isTrue();

            // Give the other two ample time to *try* — they must remain blocked outside.
            Thread.sleep(150);
            int observedPeak = peak.get();
            assertThat(lookupSemaphore.availablePermits()).isZero();

            holdGate.countDown();

            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }

            assertThat(observedPeak).isEqualTo(permits);
        } finally {
            pool.shutdownNow();
            dao.close();
        }

        assertThat(inFlight.get()).isZero();
        assertThat(peak.get()).isEqualTo(permits);
    }

    /**
     * Minimal SQLite-backed DAO that stalls inside {@code borrow()} for instrumentation.
     * Serialises connection access via a {@link ReentrantLock} like the production
     * SqliteDao does (so the per-test in-memory DB is honest about concurrency).
     */
    private static final class StallingDao extends AbstractJdbcDao {
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Connection conn;

        private final AtomicInteger inFlight;
        private final AtomicInteger peak;
        private final CountDownLatch admittedReached;
        private final CountDownLatch holdGate;
        private volatile boolean stall = false;

        StallingDao(Semaphore lookupSemaphore,
                    AtomicInteger inFlight, AtomicInteger peak,
                    CountDownLatch admittedReached, CountDownLatch holdGate) {
            super(lookupSemaphore, 0);
            this.inFlight = inFlight;
            this.peak = peak;
            this.admittedReached = admittedReached;
            this.holdGate = holdGate;
        }

        @Override
        protected Connection borrow() throws SQLException {
            if (!stall) {
                // init() / non-test paths: open + return without stalling.
                lock.lock();
                try {
                    if (conn == null || conn.isClosed()) {
                        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                    }
                    return conn;
                } catch (SQLException ex) {
                    lock.unlock();
                    throw ex;
                }
            }
            int cur = inFlight.incrementAndGet();
            peak.updateAndGet(p -> Math.max(p, cur));
            admittedReached.countDown();
            try {
                holdGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", e);
            }
            lock.lock();
            try {
                if (conn == null || conn.isClosed()) {
                    conn = DriverManager.getConnection("jdbc:sqlite::memory:");
                }
                return conn;
            } catch (SQLException ex) {
                lock.unlock();
                throw ex;
            }
        }

        @Override
        protected void release(Connection c) {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            if (stall) {
                inFlight.decrementAndGet();
            }
        }

        @Override
        protected Schema.Dialect dialect() {
            return Schema.Dialect.SQLITE;
        }

        @Override
        public boolean isHealthy() { return true; }

        @Override
        public void close() {
            lock.lock();
            try {
                if (conn != null) {
                    try { conn.close(); } catch (SQLException ignored) {}
                    conn = null;
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
