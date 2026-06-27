/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import network.vonix.guardian.core.Guardian;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 1.18.2 server entrypoint. Mirrors {@code VonixGuardianFabric} on
 * 1.20.1 / 1.21.1.
 */
public final class VonixGuardianFabric implements DedicatedServerModInitializer {

    public static final String MOD_ID = "vonixguardian";

    private static final Logger LOG = LoggerFactory.getLogger(VonixGuardianFabric.class);

    private static volatile Guardian guardian;

    @Override
    public void onInitializeServer() {
        LOG.info(Guardian.MARKER, "VonixGuardian (Fabric 1.18.2) loading.");
        try {
            FabricBootstrap.register();
            FabricEvents.register();
        } catch (Throwable t) {
            LOG.error(Guardian.MARKER, "VonixGuardian Fabric init failed", t);
        }
    }

    public static Guardian guardian() {
        return guardian;
    }

    static void setGuardian(Guardian g) {
        guardian = g;
    }
}
