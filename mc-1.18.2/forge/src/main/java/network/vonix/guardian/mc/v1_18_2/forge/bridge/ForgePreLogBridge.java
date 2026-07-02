/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.bridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.event.PreLogDispatcher;
import network.vonix.guardian.core.event.PreLogEvent;

/**
 * Forge 1.18.2 native-bus adapter for {@link PreLogEvent}.
 *
 * <p>Installed once at mod-load via {@link #wire()}. Whenever the core's
 * {@link PreLogDispatcher} consults the platform, we fire {@link Native}
 * on {@link MinecraftForge#EVENT_BUS}. Any Forge listener may cancel it
 * via the standard {@code Event#setCanceled} contract; the veto is
 * propagated back onto the core {@link PreLogEvent}.
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

        /** @return the wrapped core event (never {@code null}) */
        public PreLogEvent core() {
            return core;
        }

        /** @return the candidate action (never {@code null}) */
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
