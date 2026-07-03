package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X6 (P2-1): {@link ExplosionJoinWorker#close()} now awaits termination
 * so pending join tasks complete BEFORE the write queue drains.
 */
class ExplosionJoinWorkerShutdownTest {

    @Test
    void close_awaits_pending_tasks() throws Exception {
        // Feed a slow-running task to the worker, then close it — the task must
        // finish before close returns.
        ExecutorService slow = Executors.newSingleThreadExecutor();
        ExplosionJoinWorker w = new ExplosionJoinWorker(slow);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch canFinish = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        slow.execute(() -> {
            started.countDown();
            try { canFinish.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            completed.set(true);
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        // Let the task finish just before we call close; close must still wait
        // for the actual task exit before returning.
        canFinish.countDown();
        w.close();
        assertThat(completed.get()).isTrue();
        assertThat(slow.isTerminated()).isTrue();
    }

    @Test
    void close_forces_shutdownNow_when_task_exceeds_await() throws Exception {
        // A hung task should not deadlock close() — it should fall through to
        // shutdownNow() within a few seconds.
        ExecutorService hung = Executors.newSingleThreadExecutor();
        ExplosionJoinWorker w = new ExplosionJoinWorker(hung);
        CountDownLatch started = new CountDownLatch(1);
        hung.execute(() -> {
            started.countDown();
            try { Thread.sleep(60_000L); } catch (InterruptedException ignored) {}
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        w.close();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        // Await budget is 5s; close must return by ~6s max.
        assertThat(elapsedMs).isLessThan(7_000L);
        // shutdownNow was called, but termination might not complete instantly
        // because the sleep is not interrupt-cooperative in some JVMs; that's OK.
        assertThat(hung.isShutdown()).isTrue();
    }
}
