/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.bridge;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.world.InteractionResult;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.event.PreLogDispatcher;
import network.vonix.guardian.core.event.PreLogEvent;

/**
 * Fabric 1.19.2 native-bus adapter for {@link PreLogEvent}. See the 1.18.2
 * variant for the shared contract.
 *
 * @since 1.2.0 (W4-09)
 */
public final class FabricPreLogBridge {

    private FabricPreLogBridge() {}

    /** Listener contract: return {@link InteractionResult#FAIL} to veto. */
    @FunctionalInterface
    public interface Listener {
        InteractionResult onPreLog(Action action, PreLogEvent core);
    }

    /** Fabric event — listeners are consulted in registration order. */
    public static final Event<Listener> PRE_LOG = EventFactory.createArrayBacked(
            Listener.class,
            listeners -> (action, core) -> {
                for (Listener l : listeners) {
                    InteractionResult r = l.onPreLog(action, core);
                    if (r == InteractionResult.FAIL) {
                        return InteractionResult.FAIL;
                    }
                }
                return InteractionResult.PASS;
            }
    );

    /** Install the dispatcher. Call once from the mod entrypoint. */
    public static void wire() {
        PreLogDispatcher.setNative(evt -> {
            InteractionResult r = PRE_LOG.invoker().onPreLog(evt.action(), evt);
            if (r == InteractionResult.FAIL) {
                evt.setCancelled(true, "fabric-listener");
            }
            return evt;
        });
    }
}
