/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;

import java.util.logging.Level;

/**
 * Hytale entrypoint for Vonix Guardian.
 *
 * <p>Lifecycle contract (Hytale {@code PluginBase}):
 * {@link #setup()} registers components/systems/commands/events,
 * {@link #start()} begins live capture, {@link #shutdown()} flushes and closes.
 * Never do world or IO work in the constructor.
 */
public final class VonixGuardianPlugin extends JavaPlugin {

    public VonixGuardianPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log(
            "VonixGuardian (Hytale) setup: data dir = %s", getDataDirectory());

        // Probe registration: proves the event bus accepts a cancellable ECS event.
        getEventRegistry().register(BreakBlockEvent.class, event ->
            getLogger().at(Level.FINEST).log("break at %s", event.getTargetBlock()));
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("VonixGuardian (Hytale) started");
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("VonixGuardian (Hytale) shutting down");
    }
}
