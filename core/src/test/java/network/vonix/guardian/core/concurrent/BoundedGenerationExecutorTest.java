package network.vonix.guardian.core.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedGenerationExecutorTest {

    @Test
    void resetClosesAdmissionBeforeWaitingAndReopensAfterCleanTermination() throws Exception {
        BoundedGenerationExecutor executor = new BoundedGenerationExecutor("vg-generation-test", 1, 100L);
        CountDownLatch callback = new CountDownLatch(1);
        CountDownLatch taskRan = new CountDownLatch(1);
        assertThat(executor.reset(callback::countDown)).isTrue();
        assertThat(executor.isAccepting()).isTrue();

        executor.execute(taskRan::countDown);
        assertThat(taskRan.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.reset(callback::countDown)).isTrue();
        assertThat(callback.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.isAccepting()).isTrue();

        CountDownLatch reopened = new CountDownLatch(1);
        executor.execute(reopened::countDown);
        assertThat(reopened.await(2, TimeUnit.SECONDS)).isTrue();
        executor.reset(null);
    }

    @Test
    void configuredWorkerCountPreservesCommandConcurrencyAcrossGenerationReset() throws Exception {
        BoundedGenerationExecutor executor = new BoundedGenerationExecutor("vg-multi-worker-test", 2, 2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        executor.execute(() -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(executor.reset(null)).isTrue();
        assertThat(executor.isAccepting()).isTrue();
        executor.reset(null);
    }

    @Test
    void stuckGenerationRejectsNewWorkAndDefersCallbackUntilTermination() throws Exception {
        BoundedGenerationExecutor executor = new BoundedGenerationExecutor("vg-stuck-generation-test", 1, 25L);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch callback = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.execute(() -> {
            for (;;) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                }
            }
        });

        assertThat(executor.reset(callback::countDown)).isFalse();
        assertThat(executor.isAccepting()).isFalse();
        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(callback.await(50, TimeUnit.MILLISECONDS)).isFalse();

        release.countDown();
        assertThat(callback.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(executor.isAccepting()).isTrue();
        executor.reset(null);
    }

    @Test
    void concurrentCleanResetsLeaveReopenedGenerationUsable() throws Exception {
        BoundedGenerationExecutor executor = new BoundedGenerationExecutor("vg-concurrent-generation-test", 1, 100L);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger callbacks = new AtomicInteger();
        Runnable reset = () -> {
            try {
                start.await();
                executor.reset(callbacks::incrementAndGet);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        };
        Thread first = new Thread(reset, "vg-reset-1");
        Thread second = new Thread(reset, "vg-reset-2");
        first.start();
        second.start();
        start.countDown();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(callbacks).hasValue(2);
        assertThat(executor.isAccepting()).isTrue();
        CountDownLatch reopened = new CountDownLatch(1);
        executor.execute(reopened::countDown);
        assertThat(reopened.await(2, TimeUnit.SECONDS)).isTrue();
        executor.reset(null);
    }

    @Test
    void repeatedResetQueuesEachGenerationCallbackBeforeReopen() throws Exception {
        BoundedGenerationExecutor executor = new BoundedGenerationExecutor("vg-repeat-generation-test", 1, 25L);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbackCount = new AtomicInteger();
        executor.execute(() -> {
            for (;;) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException ignored) {
                    // Keep the synthetic old generation alive across repeated interrupts.
                }
            }
        });

        assertThat(executor.reset(callbackCount::incrementAndGet)).isFalse();
        assertThat(executor.reset(() -> callbackCount.addAndGet(100))).isFalse();
        assertThat(callbackCount).hasValue(0);
        assertThat(executor.isAccepting()).isFalse();
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (callbackCount.get() < 101 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(callbackCount).hasValue(101);
        assertThat(executor.isAccepting()).isTrue();
        executor.reset(null);
    }
}
