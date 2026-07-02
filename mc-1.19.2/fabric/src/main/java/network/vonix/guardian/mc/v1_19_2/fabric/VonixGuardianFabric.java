/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.mc.v1_19_2.fabric.bridge.FabricPreLogBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 1.19.2 server entrypoint. Wires the bootstrap + event registrations
 * on the dedicated-server lifecycle hooks.
 *
 * <p>The active {@link Guardian} facade is published in a {@code static volatile}
 * field by {@link FabricBootstrap} during {@code ServerLifecycleEvents.SERVER_STARTING}.
 */
public final class VonixGuardianFabric implements DedicatedServerModInitializer {

    /** Mod identifier — matches {@code fabric.mod.json}. */
    public static final String MOD_ID = "vonixguardian";

    private static final Logger LOG = LoggerFactory.getLogger(VonixGuardianFabric.class);

    /** Live Guardian facade — populated by {@link FabricBootstrap}. */
    private static volatile Guardian guardian;

    @Override
    public void onInitializeServer() {
        LOG.info(Guardian.MARKER, "VonixGuardian (Fabric 1.19.2) loading.");
        try {
            FabricBootstrap.register();
            FabricEvents.register();
            FabricPreLogBridge.wire();
        } catch (Throwable t) {
            LOG.error(Guardian.MARKER, "VonixGuardian Fabric init failed", t);
        }
    }

    /**
     * @return live Guardian facade, or {@code null} if the server hasn't started yet
     */
    public static Guardian guardian() {
        return guardian;
    }

    /** Internal — set by {@link FabricBootstrap}. */
    static void setGuardian(Guardian g) {
        guardian = g;
    }
}
