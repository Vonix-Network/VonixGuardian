/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.neoforge.bridge;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.event.PreLogDispatcher;
import network.vonix.guardian.core.event.PreLogEvent;

/**
 * NeoForge 1.21.1 native-bus adapter for {@link PreLogEvent}.
 *
 * <p>NeoForge dropped the {@code @Cancelable} annotation in favour of the
 * marker interface {@link ICancellableEvent}. Otherwise the shape mirrors
 * {@code ForgePreLogBridge}: fire on {@link NeoForge#EVENT_BUS}, then
 * propagate the veto onto the core {@link PreLogEvent}.
 *
 * @since 1.2.0 (W4-09)
 */
public final class NeoForgePreLogBridge {

    private NeoForgePreLogBridge() {}

    /** Cancellable NeoForge-bus wrapper around a core {@link PreLogEvent}. */
    public static final class Native extends Event implements ICancellableEvent {
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
            NeoForge.EVENT_BUS.post(n);
            if (n.isCanceled()) {
                evt.setCancelled(true, "neoforge-listener");
            }
            return evt;
        });
    }
}
