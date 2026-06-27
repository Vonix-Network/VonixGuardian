/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.mc.v1_19_2.common.Inspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.19.2 mod entrypoint.
 */
@Mod("vonixguardian")
public final class VonixGuardianForge {

    public static final String MOD_ID = "vonixguardian";

    private static final Logger LOG = LoggerFactory.getLogger(VonixGuardianForge.class);

    private static volatile Guardian guardian;

    public VonixGuardianForge() {
        LOG.info(Guardian.MARKER, "VonixGuardian (Forge 1.19.2) loading.");
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(ForgeEvents.class);
    }

    public static Guardian guardian() {
        return guardian;
    }

    static void setGuardian(Guardian g) {
        guardian = g;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent ev) {
        try {
            ForgeBootstrap.onServerStarting(ev);
        } catch (Throwable t) {
            LOG.error(Guardian.MARKER, "Failed to boot VonixGuardian", t);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent ev) {
        try {
            ForgeBootstrap.onServerStopping(ev);
        } catch (Throwable t) {
            LOG.warn(Guardian.MARKER, "VonixGuardian shutdown raised", t);
        } finally {
            Inspector.clear();
        }
    }
}
