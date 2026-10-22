/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.spi;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * Everything a Hytale-side component may need from the live runtime.
 *
 * <p>Constructed once by the bootstrap after {@code Guardian.boot(...)} returns
 * and never mutated. Consumers must not cache references across shutdown.
 *
 * <p>{@link #mainThread()} lands work on the owning world's server thread; it
 * must never be called from inside a rollback world mutation (see
 * {@code WorldMutator} thread contract in {@code core/}).
 */
public interface GuardianServices {

    Guardian guardian();

    GuardianConfig config();

    Path dataDir();

    WorldBlockAccess world();

    PlayerDirectory players();

    /** Landing executor for server-thread work. */
    Executor mainThread();

    /** {@code true} between {@code start()} and {@code shutdown()}. */
    boolean isRunning();
}
