package network.vonix.threadedhorizons.common.util;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class InvokingExecutorService implements ExecutorService {

    public static final InvokingExecutorService INSTANCE = new InvokingExecutorService();

    private final AtomicBoolean shutdown = new AtomicBoolean();

    @Override
    public void shutdown() {
        this.shutdown.set(true);
    }

    @NotNull
    @Override
    public List<Runnable> shutdownNow() {
        this.shutdown.set(true);
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return this.shutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return this.shutdown.get();
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) {
        return this.shutdown.get();
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, this);
    }

    @NotNull
    @Override
    public <T> Future<T> submit(@NotNull Runnable task, T result) {
        return CompletableFuture.runAsync(task, this).thenApply(unused -> result);
    }

    @NotNull
    @Override
    public Future<?> submit(@NotNull Runnable task) {
        return CompletableFuture.runAsync(task, this);
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(submit(task));
        }
        for (Future<T> future : futures) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // caller inspects each future
            }
        }
        return futures;
    }

    @NotNull
    @Override
    public <T> List<Future<T>> invokeAll(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit) {
        return invokeAll(tasks);
    }

    @NotNull
    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        ExecutionException last = null;
        for (Callable<T> task : tasks) {
            try {
                return task.call();
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception exception) {
                last = new ExecutionException(exception);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ExecutionException(new IllegalArgumentException("no tasks"));
    }

    @Override
    public <T> T invokeAny(@NotNull Collection<? extends Callable<T>> tasks, long timeout, @NotNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return invokeAny(tasks);
    }

    @Override
    public void execute(@NotNull Runnable command) {
        if (this.shutdown.get()) {
            throw new IllegalStateException("executor is shutdown");
        }
        command.run();
    }
}
