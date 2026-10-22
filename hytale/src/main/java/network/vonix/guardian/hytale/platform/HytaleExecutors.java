/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.platform;

import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * Writer threads and world-thread landing for {@code Guardian.boot}.
 *
 * <p>Each Hytale {@link World} is its own ticking thread.
 * {@link World#execute(Runnable)} always enqueues onto that world's task
 * queue (it does not run inline when the caller is already on the world
 * thread) and throws when the world is no longer accepting tasks. One
 * executor is therefore affine to one world. The core engine has a single
 * main-thread executor; pass the world that should own that landing spot.
 * A mutation for a different world has to be scheduled on that world's
 * executor. Waiting for another world's queue from inside {@code execute}
 * deadlocks the two tick threads.
 */
public final class HytaleExecutors {

    /** Name of every thread the writer factory creates. The engine uses one. */
    public static final String WRITER_THREAD_NAME = "VonixGuardian-Writer";

    private HytaleExecutors() {
        // utility
    }

    /**
     * Daemon threads named {@value #WRITER_THREAD_NAME}.
     *
     * @return a factory suitable for {@code Guardian.boot}'s writer argument
     */
    public static ThreadFactory writerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, WRITER_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Lands each task on {@code world}'s ticking thread via {@link World#execute}.
     *
     * @param world affinity target (never {@code null})
     * @return an executor that rejects a null task and otherwise delegates
     */
    public static Executor serverThread(World world) {
        Objects.requireNonNull(world, "world");
        return serverThread(() -> world);
    }

    /**
     * Resolves the affinity target when the task is submitted, so the executor
     * can be built before a world exists and can follow a replacement world.
     *
     * @param world supplies the affinity target; a {@code null} world rejects
     *     the task instead of running it on the caller thread
     * @return an executor bound to whatever world the supplier returns
     */
    public static Executor serverThread(Supplier<? extends World> world) {
        Objects.requireNonNull(world, "world");
        return runnable -> {
            Objects.requireNonNull(runnable, "runnable");
            World target = world.get();
            if (target == null) {
                throw new RejectedExecutionException(
                        "No Hytale world is available for server-thread landing");
            }
            target.execute(runnable);
        };
    }
}
