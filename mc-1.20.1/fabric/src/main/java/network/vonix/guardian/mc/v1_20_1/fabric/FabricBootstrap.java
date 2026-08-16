/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.DamageHistory;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.mc.v1_20_1.common.GuardianCommands;
import network.vonix.guardian.mc.v1_20_1.common.Inspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Server-lifecycle bootstrap for the Fabric loader. Mirrors
 * {@code NeoForgeBootstrap}: builds {@link Guardian} + loader-side adapters on
 * {@code SERVER_STARTING}; drains and closes on {@code SERVER_STOPPING}.
 */
public final class FabricBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(FabricBootstrap.class);

    /** Shared damage history — used by the attribution resolver and {@link FabricEvents}. */
    static volatile DamageHistory damageHistory;

    /** Shared attribution resolver — used by {@link FabricEvents}. */
    static volatile FabricAttributionResolver resolver;

    private FabricBootstrap() {
        // utility
    }

    /** Hook lifecycle callbacks. Called once from the mod entrypoint. */
    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(FabricBootstrap::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(FabricBootstrap::onServerStopping);
    }

    private static void onServerStarting(MinecraftServer server) {
        try {
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
