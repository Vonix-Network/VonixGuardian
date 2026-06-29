/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge;

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
 * Server-lifecycle bootstrap for Forge 1.18.2.
 *
 * <p>{@code MinecraftServer.getServerDirectory()} returns a {@link java.io.File}
 * on 1.18.2.
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
        ForgeEvents.reset();
    }

    /**
     * Sinytra Connector remaps {@code MinecraftServer.getServerDirectory()} to return
     * {@code java.nio.file.Path} instead of {@code java.io.File}. Resolve via
     * reflection so both signatures work. See VonixGuardian#1.0.1.
     */
    private static java.nio.file.Path resolveServerDir(net.minecraft.server.MinecraftServer server) {
        try {
            java.lang.reflect.Method m = server.getClass().getMethod("getServerDirectory");
            Object r = m.invoke(server);
            if (r instanceof java.nio.file.Path p) return p;
            if (r instanceof java.io.File f) return f.toPath();
            return java.nio.file.Paths.get("").toAbsolutePath();
        } catch (ReflectiveOperationException e) {
            LOG.warn(Guardian.MARKER, "getServerDirectory() reflection failed, using cwd", e);
            return java.nio.file.Paths.get("").toAbsolutePath();
        }
    }
}
