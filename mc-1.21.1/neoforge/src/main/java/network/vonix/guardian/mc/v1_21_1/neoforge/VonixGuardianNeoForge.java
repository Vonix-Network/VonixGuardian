/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.mc.v1_21_1.common.Inspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 1.21.x mod entrypoint.
 *
 * <p>The constructor subscribes the lifecycle listeners and event class on the
 * runtime event bus. {@link #guardian()} returns the live facade once the
 * {@link ServerStartingEvent} bootstrap has run, or {@code null} otherwise.
 */
@Mod("vonixguardian")
public final class VonixGuardianNeoForge {

    /** Mod identifier — matches {@code neoforge.mods.toml}. */
    public static final String MOD_ID = "vonixguardian";

    private static final Logger LOG = LoggerFactory.getLogger(VonixGuardianNeoForge.class);

    /** Live Guardian facade — populated by {@link NeoForgeBootstrap}. */
    private static volatile Guardian guardian;

    /**
     * NeoForge-invoked constructor.
     *
     * @param modBus this mod's mod-event bus (used for FML setup events)
     * @param container container metadata for this mod
     */
    public VonixGuardianNeoForge(IEventBus modBus, ModContainer container) {
        LOG.info(Guardian.MARKER, "VonixGuardian (NeoForge 1.21.1) loading.");
        // Lifecycle hooks live on the NeoForge runtime bus.
        NeoForge.EVENT_BUS.register(VonixGuardianNeoForge.class);
        NeoForge.EVENT_BUS.register(NeoForgeEvents.class);
    }

    /**
     * @return live Guardian facade, or {@code null} if the server hasn't started yet
     */
    public static Guardian guardian() {
        return guardian;
    }

    /** Internal — set by {@link NeoForgeBootstrap}. */
    static void setGuardian(Guardian g) {
        guardian = g;
    }

    /** Server-starting hook — boots Guardian. */
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent ev) {
        try {
            NeoForgeBootstrap.onServerStarting(ev);
        } catch (Throwable t) {
            LOG.error(Guardian.MARKER, "Failed to boot VonixGuardian", t);
        }
    }

    /** Server-stopping hook — closes Guardian and drops inspector state. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent ev) {
        try {
            NeoForgeBootstrap.onServerStopping(ev);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "VonixGuardian shutdown raised", t);
        } finally {
            Inspector.clear();
        }
    }
}
