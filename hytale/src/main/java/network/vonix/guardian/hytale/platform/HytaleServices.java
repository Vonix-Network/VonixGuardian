/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.platform;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.hytale.spi.GuardianServices;
import network.vonix.guardian.hytale.spi.PlayerDirectory;
import network.vonix.guardian.hytale.spi.WorldBlockAccess;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable {@link GuardianServices} holder.
 *
 * <p>Every dependency is fixed at construction. The only mutable state is
 * {@link #isRunning()}, which the bootstrap sets for the window between
 * {@code start()} and {@code shutdown()}. This class does not start or close
 * {@link Guardian}; the bootstrap owns that lifecycle.
 *
 * <p>{@link #toString()} omits {@link #config()} so logging the holder cannot
 * print credentials.
 */
public final class HytaleServices implements GuardianServices {

    private final Guardian guardian;
    private final GuardianConfig config;
    private final Path dataDir;
    private final WorldBlockAccess world;
    private final PlayerDirectory players;
    private final Executor mainThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * @param guardian   booted engine (never {@code null})
     * @param config     config the engine was booted with (never {@code null})
     * @param dataDir    plugin data directory (never {@code null})
     * @param world      block access (never {@code null})
     * @param players    player directory (never {@code null})
     * @param mainThread world-affine server-thread executor (never {@code null})
     */
    public HytaleServices(Guardian guardian,
                          GuardianConfig config,
                          Path dataDir,
                          WorldBlockAccess world,
                          PlayerDirectory players,
                          Executor mainThread) {
        this.guardian = Objects.requireNonNull(guardian, "guardian");
        this.config = Objects.requireNonNull(config, "config");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.world = Objects.requireNonNull(world, "world");
        this.players = Objects.requireNonNull(players, "players");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    @Override
    public Guardian guardian() {
        return guardian;
    }

    @Override
    public GuardianConfig config() {
        return config;
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public WorldBlockAccess world() {
        return world;
    }

    @Override
    public PlayerDirectory players() {
        return players;
    }

    @Override
    public Executor mainThread() {
        return mainThread;
    }

    /**
     * @param running {@code true} from plugin {@code start()} until {@code shutdown()}
     */
    public void setRunning(boolean running) {
        this.running.set(running);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String toString() {
        return "HytaleServices{dataDir=" + dataDir + ", running=" + running.get() + "}";
    }
}
