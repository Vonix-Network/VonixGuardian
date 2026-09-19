package network.vonix.threadedhorizons.common;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics;
import network.vonix.threadedhorizons.common.util.ThreadedHorizonsForkJoinWorkerThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;

public class GlobalExecutors {
    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Executors");

    private static final ThreadedHorizonsForkJoinWorkerThreadFactory factory = new ThreadedHorizonsForkJoinWorkerThreadFactory("threadedhorizons", "Threaded Horizons worker #%d", Thread.NORM_PRIORITY - 1);
    public static final ForkJoinPool executor = new ForkJoinPool(
            ThreadedHorizonsConfig.globalExecutorParallelism,
            factory,
            (thread, error) -> {
                RuntimeDiagnostics.recordUncaught(error);
                LOGGER.error("Uncaught exception on {}", thread.getName(), error);
            },
            true
    );
    public static final Executor invokingExecutor = r -> {
        if (Thread.currentThread().getThreadGroup() == factory.getThreadGroup()) {
            r.run();
        } else {
            execute(r);
        }
    };

    public static void execute(Runnable command) {
        try {
            executor.execute(command);
        } catch (RejectedExecutionException rejected) {
            RuntimeDiagnostics.recordRejected();
            throw rejected;
        }
    }

    public static void shutdown(long timeout, java.util.concurrent.TimeUnit unit) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                executor.shutdownNow();
                executor.awaitTermination(timeout, unit);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        factory.close();
    }

}
