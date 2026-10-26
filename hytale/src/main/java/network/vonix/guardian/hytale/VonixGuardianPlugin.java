/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.hytale.capture.CaptureRegistration;
import network.vonix.guardian.hytale.command.CommandRegistration;
import network.vonix.guardian.hytale.menu.MenuRegistration;
import network.vonix.guardian.hytale.platform.HytaleConfigLoader;
import network.vonix.guardian.hytale.platform.HytaleExecutors;
import network.vonix.guardian.hytale.platform.HytaleOpLookup;
import network.vonix.guardian.hytale.platform.HytalePlayerDirectory;
import network.vonix.guardian.hytale.platform.HytaleServices;
import network.vonix.guardian.hytale.world.HytaleBlockAccess;
import network.vonix.guardian.hytale.world.HytaleWorldMutator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.Executor;

/**
 * Hytale entrypoint for Vonix Guardian.
 *
 * <p>Lifecycle (Hytale {@code PluginBase}):
 * {@link #setup()} — identity logging only; the engine is not booted yet.
 * {@link #start()} — load config, boot {@link Guardian}, then register the command
 * tree and the ECS capture systems. {@link #shutdown()} — stop capture, drain the
 * write queue, close the engine.
 *
 * <p>A boot failure is contained: the plugin logs it and stays loaded in a
 * disabled state rather than taking the server down. Nothing is captured or
 * executable until {@code start()} has fully succeeded.
 *
 * <p>Block events are ECS events, not {@code IBaseEvent}s: capture subscribes by
 * installing {@code EntityEventSystem}/{@code WorldEventSystem} instances on
 * {@code EntityStore.REGISTRY}. {@code getEventRegistry()} is passed only because
 * that is the plugin-scoped hook the capture entry point accepts; nothing is
 * registered on it for block events.
 */
public final class VonixGuardianPlugin extends JavaPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(VonixGuardianPlugin.class);

    private volatile Guardian guardian;
    private volatile HytaleServices services;

    public VonixGuardianPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LOG.info(Guardian.MARKER, "VonixGuardian (Hytale) setup: data dir = {}", getDataDirectory());
    }

    @Override
    protected void start() {
        try {
            HytaleConfigLoader.Loaded loaded = HytaleConfigLoader.load(this);

            HytaleBlockAccess blocks = new HytaleBlockAccess();
            HytaleWorldMutator mutator = new HytaleWorldMutator(blocks);
            HytaleOpLookup opLookup = new HytaleOpLookup();
            HytalePlayerDirectory players = new HytalePlayerDirectory(opLookup);

            // World-affine server thread, resolved per task so the executor can be
            // built before any world exists. A missing world rejects the task instead
            // of running a world mutation off-thread.
            Executor mainThread = HytaleExecutors.serverThread(VonixGuardianPlugin::defaultWorld);

            // Plugin classloaders do not participate in JDBC service discovery, so the
            // configured driver must be initialized explicitly before the DAO asks
            // DriverManager for a connection (same rule as the Minecraft lanes).
            ensureDatabaseDriver(loaded.config());

            Guardian booted = Guardian.boot(
                    loaded.config(),
                    loaded.dataDir(),
                    mutator,
                    opLookup,
                    mainThread,
                    HytaleExecutors.writerThreadFactory());

            HytaleServices live = new HytaleServices(
                    booted, loaded.config(), loaded.dataDir(), blocks, players, mainThread);
            live.setRunning(true);
            this.guardian = booted;
            this.services = live;

            CommandRegistration.register(live, getCommandRegistry());
            MenuRegistration.register(live, getCommandRegistry());
            CaptureRegistration.register(live, getEventRegistry());

            LOG.info(Guardian.MARKER,
                    "VonixGuardian (Hytale) started: config={} data={}",
                    loaded.configFile(), loaded.dataDir());
        } catch (Throwable t) {
            // Contain: a failed boot must not take the server down.
            this.services = null;
            LOG.error(Guardian.MARKER,
                    "VonixGuardian (Hytale) failed to start; audit disabled for this session", t);
        }
    }

    @Override
    protected void shutdown() {
        HytaleServices live = this.services;
        if (live != null) {
            live.setRunning(false); // capture handlers no-op from here on
        }
        Guardian engine = this.guardian;
        if (engine != null) {
            try {
                if (!engine.awaitQueueDrained(5_000L)) {
                    LOG.warn(Guardian.MARKER,
                            "VonixGuardian write queue did not drain within 5s at shutdown");
                }
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "VonixGuardian queue drain failed at shutdown", t);
            }
            try {
                engine.close();
            } catch (Throwable t) {
                LOG.warn(Guardian.MARKER, "VonixGuardian engine close failed", t);
            }
        }
        this.guardian = null;
        this.services = null;
        LOG.info(Guardian.MARKER, "VonixGuardian (Hytale) stopped");
    }

    /** Live default world for server-thread landing, or {@code null} when none is loaded. */
    private static World defaultWorld() {
        try {
            Universe universe = Universe.get();
            return universe == null ? null : universe.getDefaultWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Initialize the configured JDBC driver inside this plugin's classloader.
     *
     * <p>{@code DriverManager} only runs service discovery against the system
     * classloader, so a driver sitting on a plugin classpath is invisible until it
     * is loaded here; its static initializer then self-registers.
     */
    private static void ensureDatabaseDriver(GuardianConfig config) throws SQLException {
        String driver = switch (config.database().type()) {
            case "sqlite" -> "org.sqlite.JDBC";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            default -> null;
        };
        if (driver == null) {
            return;
        }
        try {
            Class.forName(driver, true, VonixGuardianPlugin.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new SQLException("Configured " + config.database().type()
                    + " JDBC driver is unavailable to the Hytale plugin classloader: " + driver, ex);
        }
    }
}
