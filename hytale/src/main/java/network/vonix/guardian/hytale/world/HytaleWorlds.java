/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.world;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Objects;
import java.util.UUID;

/**
 * Live {@link World} lookup and server-thread dispatch.
 *
 * <p>Thread contract: block reads and writes in this package run only when
 * {@link World#isInThread()} is already true. They do not enqueue work and
 * wait for it. {@link #execute(World, Runnable)} always queues, because
 * {@link World#execute(Runnable)} always queues: it does not run inline on
 * the world thread. When the world is not accepting tasks that call throws
 * {@code com.hypixel.hytale.logger.sentry.SkipSentryException} wrapping
 * {@code IllegalThreadStateException}. This helper does not catch it.
 * Waiting on a task from the world thread can deadlock that thread.
 *
 * <p>The stable engine world id is {@link World#getName()} (the {@link Universe}
 * map key). {@link World#getWorldConfig()}'s UUID is a separate handle.
 */
public final class HytaleWorlds {

    private HytaleWorlds() {}

    /** Live universe, or {@code null} before boot or if the lookup throws. */
    public static Universe universe() {
        try {
            return Universe.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Live world whose engine id is {@code worldId}, or {@code null}. */
    public static World worldById(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return null;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            return universe.getWorld(worldId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Live world for {@code worldUuid}, or {@code null}. */
    public static World worldByUuid(UUID worldUuid) {
        if (worldUuid == null) {
            return null;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            return universe.getWorld(worldUuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code true} when {@code world} is non-null and the caller is on its thread. */
    public static boolean onWorldThread(World world) {
        return world != null && world.isInThread();
    }

    /**
     * Enqueue {@code task} on {@code world}. See the class thread contract.
     * Returns before the task runs, including when the caller is already on
     * the world thread.
     */
    public static void execute(World world, Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        world.execute(task);
    }

    /**
     * Run {@code task} now when the caller is on {@code world}'s thread.
     * Otherwise enqueue it and return before it runs.
     */
    public static void runOnWorldThread(World world, Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        if (world.isInThread()) {
            task.run();
            return;
        }
        world.execute(task);
    }
}
