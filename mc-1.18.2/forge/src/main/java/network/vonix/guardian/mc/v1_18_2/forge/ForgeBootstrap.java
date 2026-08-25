/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.DamageHistory;
import network.vonix.guardian.core.bootstrap.AsyncBootstrapExecutor;
import network.vonix.guardian.core.bootstrap.ServerDirectoryResolver;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.mc.v1_18_2.common.GuardianCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-lifecycle bootstrap for Forge 1.18.2.
 *
 * <p>{@code MinecraftServer.getServerDirectory()} returns a {@link java.io.File}
 * on 1.18.2.</p>
 *
 * <p>Database and schema bootstrap is deliberately off the Minecraft server
 * thread. MySQL can block while reading a server response even after TCP
 * connection succeeds; doing that work inline lets ServerHangWatchdog kill the
 * whole server before Guardian can register its commands.</p>
 */
public final class ForgeBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeBootstrap.class);
    private static final AtomicLong BOOT_GENERATION = new AtomicLong();

    private static volatile AsyncBootstrapExecutor bootstrapExecutor;
    private static volatile CompletableFuture<Guardian> bootFuture;
    private static volatile MinecraftServer activeServer;

    static volatile DamageHistory damageHistory;
    static volatile ForgeAttributionResolver resolver;

    private ForgeBootstrap() {
        // utility
    }

    /**
     * Schedule Guardian initialization without blocking Forge's server-starting
     * event thread. Commands remain deferred until the async boot succeeds.
     */
    public static void onServerStarting(ServerStartingEvent ev) {
        MinecraftServer server = ev.getServer();
        Path dataDir = resolveServerDir(server);
        long generation = BOOT_GENERATION.incrementAndGet();
        activeServer = server;

        AsyncBootstrapExecutor previous = bootstrapExecutor;
        if (previous != null) {
            previous.close();
        }
        AsyncBootstrapExecutor executor = new AsyncBootstrapExecutor("VonixGuardian-Bootstrap");
        bootstrapExecutor = executor;

        CompletableFuture<Guardian> future = executor.submit(() -> {
            Path configPath = dataDir.resolve("config").resolve("vonixguardian").resolve("config.json");
            GuardianConfig config = ConfigLoader.load(configPath);
            ForgeWorldMutator mutator = new ForgeWorldMutator(server);
            ForgeOpLookup opLookup = new ForgeOpLookup(server);
            Executor mainThread = server::execute;
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "VonixGuardian-Writer");
                t.setDaemon(true);
                return t;
            };
            return Guardian.boot(config, dataDir, mutator, opLookup, mainThread, tf);
        });
        bootFuture = future;

        future.whenComplete((g, failure) -> {
            if (failure != null) {
                if (isCurrent(server, generation)) {
                    LOG.error(Guardian.MARKER, "Failed to boot VonixGuardian asynchronously", unwrap(failure));
                }
                return;
            }
            if (!isCurrent(server, generation)) {
                closeQuietly(g);
                return;
            }
            try {
                server.execute(() -> installIfCurrent(server, generation, g));
            } catch (Throwable dispatchFailure) {
                closeQuietly(g);
                LOG.error(Guardian.MARKER, "Could not return VonixGuardian bootstrap to the server thread", dispatchFailure);
            }
        });
    }

    private static void installIfCurrent(MinecraftServer server, long generation, Guardian g) {
        if (!isCurrent(server, generation)) {
            closeQuietly(g);
            return;
        }
        damageHistory = new DamageHistory();
        resolver = new ForgeAttributionResolver(damageHistory, server);
        VonixGuardianForge.setGuardian(g);
        ForgeEvents.replayDeferredCommands(g);
        LOG.info(Guardian.MARKER, "VonixGuardian bootstrap complete.");
    }

    private static boolean isCurrent(MinecraftServer server, long generation) {
        return activeServer == server && BOOT_GENERATION.get() == generation;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static void closeQuietly(Guardian g) {
        if (g == null) return;
        try {
            g.close();
        } catch (Throwable closeFailure) {
            LOG.warn(Guardian.MARKER, "Stale VonixGuardian bootstrap cleanup raised", closeFailure);
        }
    }

    public static void onServerStopping(ServerStoppingEvent ev) {
        // Invalidate the generation before interrupting the worker. A completion
        // racing with shutdown must close its Guardian instead of installing it.
        BOOT_GENERATION.incrementAndGet();
        activeServer = null;
        CompletableFuture<Guardian> future = bootFuture;
        if (future != null) {
            future.cancel(true);
        }
        bootFuture = null;
        AsyncBootstrapExecutor executor = bootstrapExecutor;
        bootstrapExecutor = null;
        if (executor != null) {
            executor.close();
        }

        Guardian g = VonixGuardianForge.guardian();
        boolean commandsStopped = GuardianCommands.reset(() -> {
            boolean inspectorsStopped = ForgeEvents.reset(() -> {
                try {
                    if (g != null) {
                        g.close();
                    }
                } catch (Throwable t) {
                    LOG.warn(Guardian.MARKER, "Guardian.close() raised", t);
                }
            });
            if (!inspectorsStopped) {
                LOG.warn(Guardian.MARKER, "Inspector worker did not terminate; Guardian cleanup is deferred");
            }
        });
        if (damageHistory != null) {
            damageHistory.clear();
        }
        if (!commandsStopped) {
            LOG.warn(Guardian.MARKER, "Command worker did not terminate; Guardian cleanup is deferred");
        }
        VonixGuardianForge.setGuardian(null);
    }

    /**
     * Resolve via Mojmap/SRG {@code getServerDirectory} or Forge
     * {@code FMLPaths.GAMEDIR}. Never falls back to cwd.
     */
    private static java.nio.file.Path resolveServerDir(net.minecraft.server.MinecraftServer server) {
        return ServerDirectoryResolver.resolve(server, FMLPaths.GAMEDIR.get());
    }
}
