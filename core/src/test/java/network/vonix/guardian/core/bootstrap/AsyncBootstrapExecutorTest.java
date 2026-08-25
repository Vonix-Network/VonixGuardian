package network.vonix.guardian.core.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for the Forge startup watchdog boundary. */
class AsyncBootstrapExecutorTest {

    @Test
    void blocking_boot_task_runs_off_the_calling_thread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String caller = Thread.currentThread().getName();

        try (AsyncBootstrapExecutor executor = new AsyncBootstrapExecutor("vg-bootstrap-test")) {
            var future = executor.submit(() -> {
                entered.countDown();
                assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                return Thread.currentThread().getName();
            });

            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(future).isNotDone();
            release.countDown();

            assertThat(future.get(2, TimeUnit.SECONDS))
                .isNotEqualTo(caller)
                .startsWith("vg-bootstrap-test");
        }
    }
}
