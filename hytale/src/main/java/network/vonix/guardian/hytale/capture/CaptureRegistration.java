/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.capture;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentRegistry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.hytale.spi.BlockSnapshot;
import network.vonix.guardian.hytale.spi.GuardianServices;
import network.vonix.guardian.hytale.spi.PlayerDirectory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Single setup entry point for Hytale block capture.
 *
 * <p>Block events are ECS events dispatched by
 * {@code ComponentAccessor.invoke} on the entity store. They do not implement
 * {@code IBaseEvent}, so {@link EventRegistry#register(Class, java.util.function.Consumer)}
 * cannot subscribe to them. Systems are installed on {@link EntityStore#REGISTRY},
 * the same registry {@code PluginBase.getEntityStoreRegistry()} wraps.
 */
public final class CaptureRegistration {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong SKIPS = new AtomicLong();
    private static final AtomicLong LAST_SKIP_LOG_MS = new AtomicLong();

    private static volatile HytaleLogger logger = HytaleLogger.get("network.vonix.guardian.hytale.capture");

    private CaptureRegistration() {
    }

    /**
     * Register capture systems. A second call in this class loader does not
     * add a second system, so one game event is logged once.
     *
     * @param services live runtime handed over by the bootstrap after boot
     * @param registry plugin event registry; retained for the bootstrap signature.
     *                 ECS block events are not delivered on this bus
     */
    public static void register(GuardianServices services, EventRegistry registry) {
        if (services == null || registry == null) {
            throw new IllegalArgumentException("services and registry are required");
        }
        synchronized (CaptureRegistration.class) {
            if (REGISTERED.get()) {
                return;
            }
            HytaleLogger log = logger;
            try {
                ComponentRegistry<EntityStore> ecs = EntityStore.REGISTRY;
                HytaleBlockCapture.install(ecs, services);
                HytaleEnvironmentCapture.install(ecs, services);
                HytaleDamageCapture.reportUnattributed(log);
                REGISTERED.set(true);
                log.at(Level.INFO).log(
                        "Hytale capture listening for player break/place and environment break");
            } catch (RuntimeException | Error e) {
                fail(e, "registration");
                throw e;
            }
        }
    }

    /** Exceptions observed by capture handlers. */
    public static long failureCount() {
        return FAILURES.get();
    }

    /** Events dropped because identity or world id could not be resolved. */
    public static long skipCount() {
        return SKIPS.get();
    }

    static void fail(Throwable t, String where) {
        long total = FAILURES.incrementAndGet();
        HytaleLogger log = logger;
        if (log == null || t == null) {
            return;
        }
        log.at(Level.WARNING).withCause(t).log("Hytale capture %s failed (total %s)", where, total);
    }

    static void skip(String format, Object a, Object b, Object c) {
        SKIPS.incrementAndGet();
        if (!mark(LAST_SKIP_LOG_MS, 60_000L)) {
            return;
        }
        HytaleLogger log = logger;
        if (log != null) {
            log.at(Level.WARNING).log(format, a, b, c);
        }
    }

    private static boolean mark(AtomicLong lastMs, long windowMs) {
        long now = System.currentTimeMillis();
        long prev = lastMs.get();
        return now - prev >= windowMs && lastMs.compareAndSet(prev, now);
    }

    static boolean live(GuardianServices services) {
        return services != null && services.isRunning() && services.guardian() != null;
    }

    static PlayerDirectory.PlayerInfo actor(GuardianServices services, ArchetypeChunk<EntityStore> chunk, int index) {
        PlayerDirectory players = services.players();
        if (players == null || chunk == null) {
            return null;
        }
        PlayerRef ref = chunk.getComponent(index, PlayerRef.getComponentType());
        if (ref == null) {
            return null;
        }
        UUID uuid = ref.getUuid();
        if (uuid == null) {
            return null;
        }
        Optional<PlayerDirectory.PlayerInfo> resolved = players.onlineByUuid(uuid);
        if (resolved.isPresent()) {
            return resolved.get();
        }
        String username = ref.getUsername();
        if (username == null || username.isBlank()) {
            return null;
        }
        return new PlayerDirectory.PlayerInfo(uuid, username);
    }

    static String worldId(GuardianServices services, Store<EntityStore> store) {
        if (services == null || services.world() == null || store == null) {
            return null;
        }
        EntityStore entityStore = store.getExternalData();
        if (entityStore == null) {
            return null;
        }
        World world = entityStore.getWorld();
        if (world == null || world.getWorldConfig() == null) {
            return null;
        }
        UUID worldUuid = world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return null;
        }
        return services.world().worldIdOf(worldUuid);
    }

    static String blockIdOf(BlockType type) {
        if (type == null) {
            return null;
        }
        String id = type.getId();
        if (id == null || id.isBlank()) {
            return null;
        }
        return id;
    }

    /**
     * Prior rotation from the world SPI. A snapshot whose block id differs from
     * the event is left unused: the position was already replaced, so its
     * rotation is not the broken block's rotation.
     */
    static String priorRotation(GuardianServices services, String worldId, int x, int y, int z, String blockId) {
        if (services == null || services.world() == null || worldId == null || blockId == null) {
            return null;
        }
        try {
            BlockSnapshot snap = services.world().read(worldId, x, y, z);
            if (snap == null || snap.rotation() == null || snap.rotation().isBlank()) {
                return null;
            }
            if (snap.blockId() != null && !snap.blockId().equals(blockId)) {
                return null;
            }
            return snap.rotation();
        } catch (Throwable t) {
            fail(t, "rotation read");
            return null;
        }
    }

    /**
     * Admit one block row. {@link Guardian#submit(Action)} and the
     * {@code submitBlock*} helpers each consult {@link Guardian#gate()} before
     * the write queue. Exactly one of those paths runs.
     *
     * <p>The helpers cannot store {@code targetMeta}. When a rotation string is
     * present it is placed on {@code targetMeta} (and, when
     * {@code storage.persistNbt} is on, the matching block-state column) and
     * admitted with {@link Guardian#submit(Action)}.
     */
    static void admit(GuardianServices services, ActionType type, UUID actorUuid, String actorName,
                      String worldId, int x, int y, int z, String blockId, String rotation, String sourceTag) {
        Guardian guardian = services.guardian();
        if (guardian == null || type == null || worldId == null || worldId.isBlank()
                || blockId == null || blockId.isBlank()) {
            return;
        }
        String name = actorName != null ? actorName : Sentinel.UNKNOWN;
        String state = blankToNull(rotation);
        if (state == null) {
            if (type == ActionType.BLOCK_BREAK) {
                guardian.submitBlockBreak(actorUuid, name, worldId, x, y, z, blockId, sourceTag);
            } else if (type == ActionType.BLOCK_PLACE) {
                guardian.submitBlockPlace(actorUuid, name, worldId, x, y, z, blockId, sourceTag);
            }
            return;
        }
        ActionBuilder builder = new ActionBuilder()
                .type(type)
                .actorUuid(actorUuid)
                .actorName(name)
                .worldId(worldId)
                .position(x, y, z)
                .targetId(blockId)
                .targetMeta(state)
                .sourceTag(sourceTag);
        if (persistNbt(services)) {
            if (type == ActionType.BLOCK_BREAK) {
                builder.oldBlockState(state);
            } else if (type == ActionType.BLOCK_PLACE) {
                builder.newBlockState(state);
            }
        }
        guardian.submit(builder.build());
    }

    private static boolean persistNbt(GuardianServices services) {
        GuardianConfig config = services.config();
        return config != null && config.storage() != null && config.storage().persistNbt();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
