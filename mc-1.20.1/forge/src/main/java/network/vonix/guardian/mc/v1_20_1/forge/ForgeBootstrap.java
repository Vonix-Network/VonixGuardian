/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.DamageHistory;
import network.vonix.guardian.core.bootstrap.ServerDirectoryResolver;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.mc.v1_20_1.common.GuardianCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Server-lifecycle bootstrap for Forge 1.20.1.
 *
 * <p>{@code MinecraftServer.getServerDirectory()} returns a {@link java.io.File}
 * on 1.20.1 — convert via {@code .toPath()}.
 */
public final class ForgeBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeBootstrap.class);

    static volatile DamageHistory damageHistory;
    static volatile ForgeAttributionResolver resolver;

    private ForgeBootstrap() {
        // utility
    }

    public static void onServerStarting(ServerStartingEvent ev) throws Exception {
        MinecraftServer server = ev.getServer();
        Path dataDir = resolveServerDir(server);
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

        Guardian g = Guardian.boot(config, dataDir, mutator, opLookup, mainThread, tf);

        damageHistory = new DamageHistory();
        resolver = new ForgeAttributionResolver(damageHistory, server);

        VonixGuardianForge.setGuardian(g);
        ForgeEvents.replayDeferredCommands(g);
        LOG.info(Guardian.MARKER, "VonixGuardian bootstrap complete.");
    }

    public static void onServerStopping(ServerStoppingEvent ev) {
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
