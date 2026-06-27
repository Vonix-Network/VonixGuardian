/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.EventGate;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.core.logfile.JsonLinesLogFile;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.perms.PermissionResolver;
import network.vonix.guardian.core.queue.BatchedAsyncWriteQueue;
import network.vonix.guardian.core.rollback.RollbackEngine;
import network.vonix.guardian.core.rollback.UndoStack;
import network.vonix.guardian.core.rollback.WorldMutator;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.StorageFactory;
import network.vonix.guardian.core.theme.Theme;
import network.vonix.guardian.core.theme.ThemeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VonixGuardian top-level facade.
 *
 * <p>Single point of construction and access for the audit engine. Loader modules
 * (Fabric / Forge / NeoForge) build one instance during server start, hand it the
 * {@link WorldMutator}, {@link OpLevelFallback}, and main-thread {@link Executor},
 * then route MC events into the {@link EventSubmitter} surface and brigadier
 * commands through {@link #rollbackEngine()} / DAO queries.</p>
 *
 * <p>Lifecycle:
 * <pre>
 *   Guardian g = Guardian.boot(config, dataDir, mutator, opLookup, mainThreadExec, threadFactory);
 *   //  ... loader routes MC events via g.submitter().submitXxx(...)
 *   //  ... brigadier commands call g.rollbackEngine() / g.dao()
 *   g.close();   // on server stop
 * </pre>
 */
public final class Guardian implements AutoCloseable, EventSubmitter {

    /** SLF4J marker used by every log line the engine emits. */
    public static final Marker MARKER = MarkerFactory.getMarker("VONIXGUARDIAN");

    private static final Logger LOG = LoggerFactory.getLogger(Guardian.class);

    private final GuardianConfig config;
    private final GuardianDao dao;
    private final BatchedAsyncWriteQueue queue;
    private final JsonLinesLogFile logFile;             // null if disabled
    private final EventGate gate;
    private final PermissionResolver perms;
    private final RollbackEngine rollbackEngine;
    private final UndoStack undoStack;
    private final Theme theme;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong gated = new AtomicLong();

    private Guardian(GuardianConfig config,
                     GuardianDao dao,
                     BatchedAsyncWriteQueue queue,
                     JsonLinesLogFile logFile,
                     EventGate gate,
                     PermissionResolver perms,
                     RollbackEngine rollbackEngine,
                     UndoStack undoStack,
                     Theme theme) {
        this.config = config;
        this.dao = dao;
        this.queue = queue;
        this.logFile = logFile;
        this.gate = gate;
        this.perms = perms;
        this.rollbackEngine = rollbackEngine;
        this.undoStack = undoStack;
        this.theme = theme;
    }

    /**
     * Build and start a Guardian instance. Initializes the DAO schema, spins up the
     * async writer worker, opens the JSON-lines log file.
     *
     * @param config         parsed configuration
     * @param dataDir        server data root (logs are resolved against this)
     * @param mutator        loader-side world mutation impl (for rollback/restore)
     * @param opLookup       maps a UUID to its vanilla op-level (0..4)
     * @param mainThreadExec executor that lands tasks on the server thread
     * @param tf             thread factory for the queue worker
     * @return live Guardian; caller MUST eventually invoke {@link #close()}
     * @throws Exception on DAO init failure or IO error
     */
    public static Guardian boot(GuardianConfig config,
                                Path dataDir,
                                WorldMutator mutator,
                                OpLevelFallback opLookup,
                                Executor mainThreadExec,
                                ThreadFactory tf) throws Exception {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(mutator, "mutator");
        Objects.requireNonNull(opLookup, "opLookup");
        Objects.requireNonNull(mainThreadExec, "mainThreadExec");
        Objects.requireNonNull(tf, "tf");

        LOG.info(MARKER, "Booting VonixGuardian (db={}, theme={}, queue.max={})",
                config.database().type(), config.theme(), config.queue().maxSize());

        GuardianDao dao = StorageFactory.open(config);
        dao.init();

        JsonLinesLogFile logFile = null;
        if (config.logFile().enabled()) {
            Path logDir = dataDir.resolve(config.logFile().directory());
            logFile = new JsonLinesLogFile(logDir,
                    config.logFile().gzipRotated(),
                    config.logFile().retentionDays(),
                    Clock.systemUTC());
        }

        final JsonLinesLogFile logRef = logFile;
        BatchedAsyncWriteQueue queue = new BatchedAsyncWriteQueue(
                config.queue().maxSize(),
                config.queue().flushIntervalMs(),
                config.queue().batchSize(),
                batch -> {
                    dao.insertBatch(batch);
                    if (logRef != null) {
                        for (Action a : batch) {
                            logRef.append(a);
                        }
                        logRef.flush();
                    }
                },
                tf);

        EventGate gate = new EventGate(config.actions());
        PermissionResolver perms = new PermissionResolver(config.permissions(), opLookup);
        RollbackEngine rollback = new RollbackEngine(dao, mutator, mainThreadExec);
        try {
            rollback.recoverIncompleteBatches();
        } catch (Exception e) {
            LOG.warn(MARKER, "Recovery scan failed (non-fatal)", e);
        }
        UndoStack undo = new UndoStack(20);
        Theme theme = ThemeRegistry.get(config.theme());

        LOG.info(MARKER, "VonixGuardian online.");
        return new Guardian(config, dao, queue, logFile, gate, perms, rollback, undo, theme);
    }

    // -------------------------------------------------------------------- getters

    public GuardianConfig config()         { return config; }
    public GuardianDao dao()               { return dao; }
    public BatchedAsyncWriteQueue queue()  { return queue; }
    public PermissionResolver perms()      { return perms; }
    public RollbackEngine rollbackEngine() { return rollbackEngine; }
    public UndoStack undoStack()           { return undoStack; }
    public Theme theme()                   { return theme; }
    public EventSubmitter submitter()      { return this; }
    public long submitted()                { return submitted.get(); }
    public long gated()                    { return gated.get(); }

    // -------------------------------------------------------------------- EventSubmitter

    private static String orUnknown(String name) {
        return name != null ? name : Sentinel.UNKNOWN;
    }

    private ActionBuilder seed(ActionType type, UUID actorUuid, String actorName, String worldId) {
        return new ActionBuilder()
                .type(type)
                .actorUuid(actorUuid)
                .actorName(orUnknown(actorName))
                .worldId(worldId);
    }

    @Override
    public void submit(Action a) {
        if (a == null) {
            return;
        }
        if (!gate.shouldLog(a)) {
            gated.incrementAndGet();
            return;
        }
        submitted.incrementAndGet();
        queue.submit(a);
    }

    @Override
    public void submitBlockBreak(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.BLOCK_BREAK, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitBlockPlace(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.BLOCK_PLACE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitContainerChange(UUID actorUuid, String actorName, String worldId,
                                      int x, int y, int z, String itemId, int delta, String sourceTag) {
        if (delta == 0) {
            return;
        }
        submit(seed(delta > 0 ? ActionType.CONTAINER_DEPOSIT : ActionType.CONTAINER_WITHDRAW,
                    actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(Math.abs(delta)).sourceTag(sourceTag).build());
    }

    @Override
    public void submitItemDrop(UUID actorUuid, String actorName, String worldId,
                               int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.ITEM_DROP, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    @Override
    public void submitItemPickup(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.ITEM_PICKUP, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    @Override
    public void submitEntityKill(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String entityType, String sourceTag) {
        submit(seed(ActionType.ENTITY_KILL, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag).build());
    }

    @Override
    public void submitExplosion(UUID actorUuid, String actorName, String worldId,
                                int x, int y, int z, String affectedJoined, String sourceTag) {
        submit(new ActionBuilder()
                .type(ActionType.EXPLOSION)
                .actorUuid(actorUuid)
                .actorName(actorName != null ? actorName : Sentinel.EXPLOSION)
                .worldId(worldId)
                .position(x, y, z)
                .targetId(affectedJoined)
                .sourceTag(sourceTag)
                .build());
    }

    @Override
    public void submitChat(UUID actorUuid, String actorName, String worldId, String message) {
        submit(seed(ActionType.CHAT, actorUuid, actorName, worldId).targetId(message).build());
    }

    @Override
    public void submitCommand(UUID actorUuid, String actorName, String worldId, String command) {
        submit(seed(ActionType.COMMAND, actorUuid, actorName, worldId).targetId(command).build());
    }

    @Override
    public void submitSign(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String joinedLines) {
        submit(seed(ActionType.SIGN, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(joinedLines).build());
    }

    @Override
    public void submitSessionJoin(UUID actorUuid, String actorName, String worldId, String ipOrHash) {
        submit(seed(ActionType.SESSION_JOIN, actorUuid, actorName, worldId)
                .targetId(ipOrHash != null ? ipOrHash : "").build());
    }

    @Override
    public void submitSessionLeave(UUID actorUuid, String actorName, String worldId, String reason) {
        submit(seed(ActionType.SESSION_LEAVE, actorUuid, actorName, worldId)
                .targetId(reason != null ? reason : "").build());
    }

    @Override
    public void submitUsernameChange(UUID actorUuid, String newName, String worldId, String oldName) {
        submit(seed(ActionType.USERNAME_CHANGE, actorUuid, newName, worldId)
                .targetId((oldName != null ? oldName : "?") + " -> " + (newName != null ? newName : "?"))
                .build());
    }

    // -------------------------------------------------------------------- expansion: block

    @Override
    public void submitBurn(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.BURN, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitIgnite(UUID actorUuid, String actorName, String worldId,
                             int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.IGNITE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitFade(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.FADE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitForm(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.FORM, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitSpread(UUID actorUuid, String actorName, String worldId,
                             int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.SPREAD, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitDispense(UUID actorUuid, String actorName, String worldId,
                               int x, int y, int z, String itemId, String sourceTag) {
        submit(seed(ActionType.DISPENSE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitPistonExtend(UUID actorUuid, String actorName, String worldId,
                                   int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.PISTON_EXTEND, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitPistonRetract(UUID actorUuid, String actorName, String worldId,
                                    int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.PISTON_RETRACT, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitBucketEmpty(UUID actorUuid, String actorName, String worldId,
                                  int x, int y, int z, String fluidOrBlockId, String sourceTag) {
        submit(seed(ActionType.BUCKET_EMPTY, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(fluidOrBlockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitBucketFill(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String fluidOrBlockId, String sourceTag) {
        submit(seed(ActionType.BUCKET_FILL, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(fluidOrBlockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitLeavesDecay(UUID actorUuid, String actorName, String worldId,
                                  int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.LEAVES_DECAY, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
    }

    @Override
    public void submitEntityChangeBlock(UUID actorUuid, String actorName, String worldId,
                                        int x, int y, int z,
                                        String oldBlockId, String newBlockId, String sourceTag) {
        String target = (oldBlockId != null ? oldBlockId : "?") + " -> "
                + (newBlockId != null ? newBlockId : "?");
        submit(seed(ActionType.ENTITY_CHANGE_BLOCK, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(target).sourceTag(sourceTag).build());
    }

    // -------------------------------------------------------------------- expansion: container/item

    @Override
    public void submitInventoryDeposit(UUID actorUuid, String actorName, String worldId,
                                       int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.INVENTORY_DEPOSIT, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    @Override
    public void submitInventoryWithdraw(UUID actorUuid, String actorName, String worldId,
                                        int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.INVENTORY_WITHDRAW, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    @Override
    public void submitHopperPush(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.HOPPER_PUSH, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    @Override
    public void submitHopperPull(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.HOPPER_PULL, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    @Override
    public void submitItemCraft(UUID actorUuid, String actorName, String worldId,
                                int x, int y, int z, String itemId, int amount, String sourceTag) {
        submit(seed(ActionType.ITEM_CRAFT, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag).build());
    }

    // -------------------------------------------------------------------- expansion: entities

    @Override
    public void submitEntitySpawn(UUID actorUuid, String actorName, String worldId,
                                  int x, int y, int z, String entityType, String sourceTag) {
        submit(seed(ActionType.ENTITY_SPAWN, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag).build());
    }

    @Override
    public void submitEntityInteract(UUID actorUuid, String actorName, String worldId,
                                     int x, int y, int z, String entityType, String sourceTag) {
        submit(seed(ActionType.ENTITY_INTERACT, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag).build());
    }

    @Override
    public void submitHangingPlace(UUID actorUuid, String actorName, String worldId,
                                   int x, int y, int z, String entityType, String sourceTag) {
        submit(seed(ActionType.HANGING_PLACE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag).build());
    }

    @Override
    public void submitHangingBreak(UUID actorUuid, String actorName, String worldId,
                                   int x, int y, int z, String entityType, String sourceTag) {
        submit(seed(ActionType.HANGING_BREAK, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag).build());
    }

    // -------------------------------------------------------------------- expansion: world

    @Override
    public void submitStructureGrow(UUID actorUuid, String actorName, String worldId,
                                    int x, int y, int z, String structureId, String sourceTag) {
        submit(seed(ActionType.STRUCTURE_GROW, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(structureId).sourceTag(sourceTag).build());
    }

    // -------------------------------------------------------------------- expansion: interact

    @Override
    public void submitClick(UUID actorUuid, String actorName, String worldId,
                            int x, int y, int z, String targetId, String sourceTag) {
        submit(seed(ActionType.CLICK, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(targetId).sourceTag(sourceTag).build());
    }

    // -------------------------------------------------------------------- lifecycle

    /**
     * Drain pending writes, close the log file, and shut down the DAO pool.
     * Safe to call multiple times.
     */
    public void shutdown() {
        LOG.info(MARKER, "Shutting down VonixGuardian (submitted={}, gated={}, queue.depth={}, queue.dropped={})",
                submitted.get(), gated.get(), queue.depth(), queue.dropped());
        try {
            queue.drainAndFlush(30_000L);
        } catch (Exception e) {
            LOG.warn(MARKER, "Queue drain raised", e);
        }
        try {
            if (logFile != null) {
                logFile.close();
            }
        } catch (Exception e) {
            LOG.warn(MARKER, "Log-file close raised", e);
        }
        try {
            dao.close();
        } catch (Exception e) {
            LOG.warn(MARKER, "DAO close raised", e);
        }
        LOG.info(MARKER, "VonixGuardian offline.");
    }

    @Override
    public void close() {
        shutdown();
    }
}
