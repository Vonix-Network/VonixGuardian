/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.DamageHistory;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.mc.v1_18_2.common.GuardianCommands;
import network.vonix.guardian.mc.v1_18_2.common.Inspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Server-lifecycle bootstrap for Fabric 1.18.2.
 *
 * <p>1.18.2 quirk: {@code server.getServerDirectory()} returns a {@link java.io.File}.
 * Converted to {@link Path} via {@code .toPath()}.
 */
public final class FabricBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(FabricBootstrap.class);

    static volatile DamageHistory damageHistory;
    static volatile FabricAttributionResolver resolver;

    private FabricBootstrap() {
        // utility
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(FabricBootstrap::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(FabricBootstrap::onServerStopping);
    }

    private static void onServerStarting(MinecraftServer server) {
        try {
            // 1.18.2: getServerDirectory() returns File.
            Path dataDir = server.getServerDirectory().toPath();
            Path configPath = dataDir.resolve("config").resolve("vonixguardian").resolve("config.json");
            GuardianConfig config = ConfigLoader.load(configPath);

            FabricWorldMutator mutator = new FabricWorldMutator(server);
            FabricOpLookup opLookup = new FabricOpLookup(server);
            Executor mainThread = server::execute;
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "VonixGuardian-Writer");
                t.setDaemon(true);
                return t;
            };

            Guardian g = Guardian.boot(config, dataDir, mutator, opLookup, mainThread, tf);

            damageHistory = new DamageHistory();
            resolver = new FabricAttributionResolver(damageHistory, server);

            VonixGuardianFabric.setGuardian(g);
            FabricEvents.replayDeferredCommands(g);
            LOG.info(Guardian.MARKER, "VonixGuardian bootstrap complete.");
        } catch (Throwable t) {
            LOG.error(Guardian.MARKER, "Failed to boot VonixGuardian", t);
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        Guardian g = VonixGuardianFabric.guardian();
        boolean commandsStopped = GuardianCommands.reset(() -> {
            boolean inspectorsStopped = FabricEvents.reset(() -> {
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
        Inspector.clear();
        if (!commandsStopped) {
            LOG.warn(Guardian.MARKER, "Command worker did not terminate; Guardian cleanup is deferred");
        }
        VonixGuardianFabric.setGuardian(null);
    }
}
