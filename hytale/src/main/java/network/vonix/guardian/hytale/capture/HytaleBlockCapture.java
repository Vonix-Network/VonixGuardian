/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.capture;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.hytale.spi.GuardianServices;
import network.vonix.guardian.hytale.spi.PlayerDirectory;
import org.joml.Vector3i;

import java.util.Set;

/**
 * Player block break and place. The game invokes these events on the acting
 * entity ({@code ComponentAccessor.invoke(ref, event)}) before it commits the
 * edit. The query is {@link PlayerRef}, so the actor is that player.
 */
public final class HytaleBlockCapture {

    private HytaleBlockCapture() {
    }

    static void install(ComponentRegistry<EntityStore> registry, GuardianServices services) {
        if (PlayerRef.getComponentType() == null) {
            throw new IllegalStateException("PlayerRef component type is not registered");
        }
        if (!registry.hasSystemClass(BreakByPlayer.class)) {
            registry.registerSystem(new BreakByPlayer(services));
        }
        if (!registry.hasSystemClass(PlaceByPlayer.class)) {
            registry.registerSystem(new PlaceByPlayer(services));
        }
    }

    static final class BreakByPlayer extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        private final GuardianServices services;

        BreakByPlayer(GuardianServices services) {
            super(BreakBlockEvent.class);
            this.services = services;
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, BreakBlockEvent event) {
            try {
                capture(index, chunk, store, event);
            } catch (Throwable t) {
                CaptureRegistration.fail(t, "block break");
            }
        }

        private void capture(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                             BreakBlockEvent event) {
            if (!CaptureRegistration.live(services) || event == null || event.isCancelled()) {
                return;
            }
            Vector3i pos = event.getTargetBlock();
            String blockId = CaptureRegistration.blockIdOf(event.getBlockType());
            if (pos == null || blockId == null) {
                return;
            }
            PlayerDirectory.PlayerInfo actor = CaptureRegistration.actor(services, chunk, index);
            if (actor == null) {
                CaptureRegistration.skip("block break skipped: player name unavailable at %s,%s,%s",
                        pos.x(), pos.y(), pos.z());
                return;
            }
            String worldId = CaptureRegistration.worldId(services, store);
            if (worldId == null) {
                CaptureRegistration.skip("block break skipped: world id unavailable at %s,%s,%s",
                        pos.x(), pos.y(), pos.z());
                return;
            }
            int x = pos.x();
            int y = pos.y();
            int z = pos.z();
            String rotation = CaptureRegistration.priorRotation(services, worldId, x, y, z, blockId);
            CaptureRegistration.admit(services, ActionType.BLOCK_BREAK, actor.uuid(), actor.name(),
                    worldId, x, y, z, blockId, rotation, null);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return RootDependency.lastSet();
        }
    }

    static final class PlaceByPlayer extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

        private final GuardianServices services;

        PlaceByPlayer(GuardianServices services) {
            super(PlaceBlockEvent.class);
            this.services = services;
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, PlaceBlockEvent event) {
            try {
                capture(index, chunk, store, event);
            } catch (Throwable t) {
                CaptureRegistration.fail(t, "block place");
            }
        }

        private void capture(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                             PlaceBlockEvent event) {
            if (!CaptureRegistration.live(services) || event == null || event.isCancelled()) {
                return;
            }
            Vector3i pos = event.getTargetBlock();
            String blockId = placedBlockId(event.getItemInHand());
            if (pos == null || blockId == null) {
                return;
            }
            PlayerDirectory.PlayerInfo actor = CaptureRegistration.actor(services, chunk, index);
            if (actor == null) {
                CaptureRegistration.skip("block place skipped: player name unavailable at %s,%s,%s",
                        pos.x(), pos.y(), pos.z());
                return;
            }
            String worldId = CaptureRegistration.worldId(services, store);
            if (worldId == null) {
                CaptureRegistration.skip("block place skipped: world id unavailable at %s,%s,%s",
                        pos.x(), pos.y(), pos.z());
                return;
            }
            CaptureRegistration.admit(services, ActionType.BLOCK_PLACE, actor.uuid(), actor.name(),
                    worldId, pos.x(), pos.y(), pos.z(), blockId, rotationText(event.getRotation()), null);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return RootDependency.lastSet();
        }
    }

    /**
     * {@code BlockPlaceUtils} resolves the placed block with
     * {@link ItemStack#getBlockKey()} after the event returns. The event itself
     * has no {@code BlockType}.
     */
    static String placedBlockId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String key = stack.getBlockKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        return key;
    }

    static String rotationText(RotationTuple rotation) {
        if (rotation == null) {
            return null;
        }
        String text = rotation.toString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }
}
