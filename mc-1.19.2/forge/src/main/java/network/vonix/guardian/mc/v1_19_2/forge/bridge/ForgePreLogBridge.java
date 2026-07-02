/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge.bridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.event.PreLogDispatcher;
import network.vonix.guardian.core.event.PreLogEvent;

/**
 * Forge 1.19.2 native-bus adapter for {@link PreLogEvent}. See
 * {@code ForgePreLogBridge} on 1.18.2 / 1.20.1 for the shared contract.
 *
 * @since 1.2.0 (W4-09)
 */
public final class ForgePreLogBridge {

    private ForgePreLogBridge() {}

    /** Cancellable Forge-bus wrapper around a core {@link PreLogEvent}. */
    @Cancelable
    public static final class Native extends Event {
        private final PreLogEvent core;

        public Native(PreLogEvent core) {
            this.core = core;
        }

        public PreLogEvent core() {
            return core;
        }

        public Action action() {
            return core.action();
        }
    }

    /** Install the dispatcher. Call once from the mod entrypoint. */
    public static void wire() {
        PreLogDispatcher.setNative(evt -> {
            Native n = new Native(evt);
            boolean cancelled = MinecraftForge.EVENT_BUS.post(n);
            if (cancelled) {
                evt.setCancelled(true, "forge-listener");
            }
            return evt;
        });
    }
}
