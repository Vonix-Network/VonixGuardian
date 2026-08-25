package network.vonix.guardian.core.bootstrap;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small daemon executor for startup work that may block on external services.
 *
 * <p>Loader lifecycle handlers must not perform JDBC bootstrap directly on the
 * Minecraft server thread. This runner gives them a named, single-purpose lane
 * whose tasks can be interrupted during server shutdown.</p>
 */
public final class AsyncBootstrapExecutor implements AutoCloseable {

    private final ExecutorService executor;

    public AsyncBootstrapExecutor(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        if (threadName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /** Submit one blocking-capable startup task without running it inline. */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
