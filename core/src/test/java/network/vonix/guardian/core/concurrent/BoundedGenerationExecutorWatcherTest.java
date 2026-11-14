package network.vonix.guardian.core.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A generation that outlives {@code stopWaitMillis} is closed to admission and its
 * deferred callbacks are run by a watcher thread once the pool really terminates.
 *
 * <p>Those callbacks are the resource-teardown chain
 * ({@code ForgeBootstrap.onServerStopping} passes {@code Guardian.close()}, which
 * drains the write queue and closes the DAO). The watcher used to treat
 * {@link InterruptedException} as cancellation: it logged a warning, returned, and
 * nothing ever ran the callbacks. Admission stayed closed and the teardown never
 * happened for the life of the JVM.</p>
 */
class BoundedGenerationExecutorWatcherTest {

    @Test
    void interruptedWatcherStillRunsDeferredCallbacksWhenThePoolTerminates() throws Exception {
        BoundedGenerationExecutor executor =
                new BoundedGenerationExecutor("vg-watcher-interrupt-test", 1, 25L);

        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callbackRan = new CountDownLatch(1);

        executor.execute(() -> {
            for (;;) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException ex) {
                    sawInterrupt.set(true);
                }
            }
        });

        // Stuck generation: reset cannot terminate it, so a watcher takes ownership.
        assertThat(executor.reset(() -> {
            callbacks.incrementAndGet();
            callbackRan.countDown();
        })).isFalse();
        assertThat(executor.isAccepting()).isFalse();

        Thread watcher = awaitThread("vg-watcher-interrupt-test-shutdown-watcher");
        assertThat(watcher)
                .as("a stuck generation must be owned by a termination watcher")
                .isNotNull();

        // A stray interrupt must be a "keep waiting", not a "give up".
        watcher.interrupt();

        release.countDown();
        assertThat(callbackRan.await(5, TimeUnit.SECONDS))
                .as("deferred teardown callbacks must still run after a watcher interrupt")
                .isTrue();
        assertThat(callbacks).hasValue(1);
        assertThat(sawInterrupt).isTrue();
        assertThat(executor.isAccepting())
                .as("the replacement generation must open once the old one terminates")
                .isTrue();

        executor.reset(null);
    }

    @Test
    void repeatedInterruptsDoNotStrandTheGeneration() throws Exception {
        BoundedGenerationExecutor executor =
                new BoundedGenerationExecutor("vg-watcher-repeat-test", 1, 25L);

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callbackRan = new CountDownLatch(1);

        executor.execute(() -> {
            for (;;) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    // Deliberately survive interrupts so the generation stays stuck.
                }
            }
        });

        assertThat(executor.reset(() -> {
            callbacks.incrementAndGet();
            callbackRan.countDown();
        })).isFalse();

        Thread watcher = awaitThread("vg-watcher-repeat-test-shutdown-watcher");
        assertThat(watcher).isNotNull();
        for (int i = 0; i < 5; i++) {
            watcher.interrupt();
            Thread.sleep(5L);
        }

        release.countDown();
        assertThat(callbackRan.await(5, TimeUnit.SECONDS))
                .as("repeated interrupts must not abandon the callback chain")
                .isTrue();
        assertThat(callbacks).hasValue(1);
        assertThat(executor.isAccepting()).isTrue();

        executor.reset(null);
    }

    private static Thread awaitThread(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals(name) && t.isAlive()) {
                    return t;
                }
            }
            Thread.sleep(5L);
        }
        return null;
    }
}
