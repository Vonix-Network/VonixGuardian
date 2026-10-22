/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.capture;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.EnvironmentBreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.hytale.spi.GuardianServices;
import org.joml.Vector3i;

/**
 * Breaks with no entity causer. {@code BlockHarvestUtils.performBlockBreak}
 * invokes {@link EnvironmentBreakBlockEvent} only on the branch where the
 * breaker ref is null (fire, explosion, physics, and any other non-entity
 * removal that shares that path). The event carries the broken block and its
 * position, not which environment effect did it.
 *
 * <p>Actor is {@link Sentinel#UNKNOWN}, the same {@code "#unknown"} string as
 * {@link ActionBuilder#UNKNOWN_ACTOR_NAME}. The source tag is {@code environment}.
 * Fire and explosion are not guessed.
 */
public final class HytaleEnvironmentCapture {

    /** Same value as {@link ActionBuilder#UNKNOWN_ACTOR_NAME}. */
    static final String ACTOR = Sentinel.UNKNOWN;
    static final String SOURCE = "environment";

    static {
        if (!Sentinel.UNKNOWN.equals(ActionBuilder.UNKNOWN_ACTOR_NAME)) {
            throw new IllegalStateException("Sentinel.UNKNOWN and ActionBuilder.UNKNOWN_ACTOR_NAME diverged");
        }
    }

    private HytaleEnvironmentCapture() {
    }

    static void install(ComponentRegistry<EntityStore> registry, GuardianServices services) {
        if (!registry.hasSystemClass(OnEnvironmentBreak.class)) {
            registry.registerSystem(new OnEnvironmentBreak(services));
        }
    }

    static final class OnEnvironmentBreak extends WorldEventSystem<EntityStore, EnvironmentBreakBlockEvent> {

        private final GuardianServices services;

        OnEnvironmentBreak(GuardianServices services) {
            super(EnvironmentBreakBlockEvent.class);
            this.services = services;
        }

        @Override
        public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer,
                           EnvironmentBreakBlockEvent event) {
            try {
                capture(store, event);
            } catch (Throwable t) {
                CaptureRegistration.fail(t, "environment break");
            }
        }

        private void capture(Store<EntityStore> store, EnvironmentBreakBlockEvent event) {
            if (!CaptureRegistration.live(services) || event == null) {
                return;
            }
            Vector3i pos = event.getTargetBlock();
            String blockId = CaptureRegistration.blockIdOf(event.getBlockType());
            if (pos == null || blockId == null) {
                return;
            }
            String worldId = CaptureRegistration.worldId(services, store);
            if (worldId == null) {
                CaptureRegistration.skip("environment break skipped: world id unavailable at %s,%s,%s",
                        pos.x(), pos.y(), pos.z());
                return;
            }
            int x = pos.x();
            int y = pos.y();
            int z = pos.z();
            String rotation = CaptureRegistration.priorRotation(services, worldId, x, y, z, blockId);
            CaptureRegistration.admit(services, ActionType.BLOCK_BREAK, null, ACTOR,
                    worldId, x, y, z, blockId, rotation, SOURCE);
        }
    }
}
