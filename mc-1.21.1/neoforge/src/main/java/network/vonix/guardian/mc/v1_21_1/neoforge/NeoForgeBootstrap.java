/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
 * Server-lifecycle bootstrap. Builds Guardian and the loader-side wiring on
 * {@link ServerAboutToStartEvent} (fires BEFORE {@code RegisterCommandsEvent} so
 * the {@code /vg} brigadier tree can register against a live Guardian); shuts
 * it down on {@link ServerStoppingEvent}.
 */
public final class NeoForgeBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeBootstrap.class);

    /** Shared damage history — used by the attribution resolver and {@link NeoForgeEvents}. */
    static volatile DamageHistory damageHistory;

    /** Shared attribution resolver — used by {@link NeoForgeEvents}. */
    static volatile NeoForgeAttributionResolver resolver;

    private NeoForgeBootstrap() {
        // utility
    }

    /**
     * Boot Guardian. Loads config, builds loader-side adapters, wires the facade.
     * Fires on {@link ServerAboutToStartEvent} — this is the EARLIEST event with
     * access to the running {@link MinecraftServer}, and importantly fires BEFORE
     * {@code RegisterCommandsEvent} so the {@code /vg} brigadier tree can see a
     * non-null Guardian.
     *
     * @param ev NeoForge server-about-to-start event
     * @throws Exception on config or DAO failure (bubbles to the caller; logged there)
     */
    public static void onServerStarting(ServerAboutToStartEvent ev) throws Exception {
        MinecraftServer server = ev.getServer();
        Path dataDir = server.getServerDirectory();
        Path configPath = dataDir.resolve("config").resolve("vonixguardian").resolve("config.json");
        GuardianConfig config = ConfigLoader.load(configPath);

        NeoForgeWorldMutator mutator = new NeoForgeWorldMutator(server);
        NeoForgeOpLookup opLookup = new NeoForgeOpLookup(server);
        Executor mainThread = server::execute;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "VonixGuardian-Writer");
            t.setDaemon(true);
            return t;
        };

        Guardian g = Guardian.boot(config, dataDir, mutator, opLookup, mainThread, tf);

        damageHistory = new DamageHistory();
        resolver = new NeoForgeAttributionResolver(damageHistory, server);

        VonixGuardianNeoForge.setGuardian(g);
        NeoForgeEvents.replayDeferredCommands(g);
        LOG.info(Guardian.MARKER, "VonixGuardian bootstrap complete.");
    }

    /**
     * Drain and close Guardian.
     *
     * @param ev NeoForge server-stopping event
     */
    public static void onServerStopping(ServerStoppingEvent ev) {
        Guardian g = VonixGuardianNeoForge.guardian();
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
        VonixGuardianNeoForge.setGuardian(null);
    }
}
