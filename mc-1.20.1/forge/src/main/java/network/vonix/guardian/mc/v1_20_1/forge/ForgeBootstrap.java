/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.attribution.DamageHistory;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
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
        Path dataDir = server.getServerDirectory().toPath();
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
        LOG.info(Guardian.MARKER, "VonixGuardian bootstrap complete.");
    }

    public static void onServerStopping(ServerStoppingEvent ev) {
        Guardian g = VonixGuardianForge.guardian();
        if (g != null) {
            try {
                g.close();
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "Guardian.close() raised", t);
            }
        }
        if (damageHistory != null) {
            damageHistory.clear();
        }
        VonixGuardianForge.setGuardian(null);
    }
}
