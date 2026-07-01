/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.EventGate;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.core.filter.VanillaGrieferSet;
import network.vonix.guardian.core.logfile.JsonLinesLogFile;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.perms.PermissionResolver;
import network.vonix.guardian.core.purge.AutoPurgeScheduler;
import network.vonix.guardian.core.query.InspectorLookup;
import network.vonix.guardian.core.queue.BatchedAsyncWriteQueue;
import network.vonix.guardian.core.queue.EntityBlockChangeCoalescer;
import network.vonix.guardian.core.rollback.PurgeEngine;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    private volatile GuardianConfig config;
    private final GuardianDao dao;
    private final BatchedAsyncWriteQueue queue;
    private volatile EventGate gate;
    private final PermissionResolver perms;
    private final RollbackEngine rollbackEngine;
    private final PurgeEngine purgeEngine;
    private final UndoStack undoStack;
    private volatile Theme theme;
    private final EntityBlockChangeCoalescer entityBlockCoalescer;
    /** Latched at boot when logFile.enabled; hot-swap of enabled flag flips this AtomicReference. */
    private final AtomicReference<JsonLinesLogFile> logFileRef;
    /** Server data-dir root, kept so reload can rebuild a JsonLinesLogFile at the same relative path. */
    private final Path dataDir;
    /** Path to the on-disk config.json; kept so /vg reload can re-read it without any cell-side plumbing. */
    private volatile Path configPath;
    private final AutoPurgeScheduler autoPurgeScheduler;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong gated = new AtomicLong();

    private Guardian(GuardianConfig config,
                     GuardianDao dao,
                     BatchedAsyncWriteQueue queue,
                     AtomicReference<JsonLinesLogFile> logFileRef,
                     EventGate gate,
                     PermissionResolver perms,
                     RollbackEngine rollbackEngine,
                     PurgeEngine purgeEngine,
                     UndoStack undoStack,
                     Theme theme,
                     EntityBlockChangeCoalescer entityBlockCoalescer,
                     Path dataDir,
                     AutoPurgeScheduler autoPurgeScheduler) {
        this.config = config;
        this.dao = dao;
        this.queue = queue;
        this.gate = gate;
        this.perms = perms;
        this.rollbackEngine = rollbackEngine;
        this.purgeEngine = purgeEngine;
        this.undoStack = undoStack;
        this.theme = theme;
        this.entityBlockCoalescer = entityBlockCoalescer;
        this.logFileRef = logFileRef;
        this.dataDir = dataDir;
        this.autoPurgeScheduler = autoPurgeScheduler;
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
        final AtomicReference<JsonLinesLogFile> logHolder = new AtomicReference<>(logRef);
        BatchedAsyncWriteQueue queue = new BatchedAsyncWriteQueue(
                config.queue().maxSize(),
                config.queue().flushIntervalMs(),
                config.queue().batchSize(),
                batch -> {
                    dao.insertBatch(batch);
                    JsonLinesLogFile lf = logHolder.get();
                    if (lf != null) {
                        for (Action a : batch) {
                            lf.append(a);
                        }
                        lf.flush();
                    }
                },
                tf);

        EventGate gate = new EventGate(config.actions());
        // W3-B11: public cancellable pre-log hook. Registered LAST so that
        // cheaper built-in hooks (per-world overrides — B5, blacklist.txt — B6)
        // get first crack before we pay a native-bus dispatch.
        gate.addHook(new network.vonix.guardian.core.event.PreLogEventHook());
        PermissionResolver perms = new PermissionResolver(config.permissions(), opLookup);
        RollbackEngine rollback = new RollbackEngine(dao, mutator, mainThreadExec);
        PurgeEngine purgeEng = new PurgeEngine(dao);
        try {
            rollback.recoverIncompleteBatches();
        } catch (Exception e) {
            LOG.warn(MARKER, "Recovery scan failed (non-fatal)", e);
        }
        UndoStack undo = new UndoStack(20);
        Theme theme = ThemeRegistry.get(config.theme());

        // Coalescer: window > 0 enables, negative/zero disables. Backward-compat
        // fills 0→500 in ConfigLoader; negative is an explicit operator opt-out.
        long coalWindow = config.actions().entityBlockChangeCoalesceWindowMs();
        int  coalMax    = config.actions().entityBlockChangeMaxTracked();
        EntityBlockChangeCoalescer coal = null;
        if (coalWindow > 0 && coalMax > 0) {
            coal = new EntityBlockChangeCoalescer(coalWindow, coalMax);
            LOG.info(MARKER, "EntityBlockChange coalescer enabled (window={}ms, maxTracked={})",
                    coalWindow, coalMax);
        } else {
            LOG.warn(MARKER, "EntityBlockChange coalescer DISABLED (window={}, maxTracked={}); " +
                    "expect queue-full spam on high-cardinality entity-change modpacks",
                    coalWindow, coalMax);
        }

        LOG.info(MARKER, "VonixGuardian online.");
        AutoPurgeScheduler autoPurge = AutoPurgeScheduler.create(config, purgeEng, dao);
        autoPurge.start();
        return new Guardian(config, dao, queue, logHolder, gate, perms, rollback, purgeEng, undo, theme, coal, dataDir, autoPurge);
    }

    // -------------------------------------------------------------------- getters

    public GuardianConfig config()         { return config; }
    public GuardianDao dao()               { return dao; }
    public BatchedAsyncWriteQueue queue()  { return queue; }
    public PermissionResolver perms()      { return perms; }
    public RollbackEngine rollbackEngine() { return rollbackEngine; }
    /** CP-1:1 purge entry point — enforces config.purge() minimum-age floor. */
    public PurgeEngine purgeEngine()       { return purgeEngine; }
    public UndoStack undoStack()           { return undoStack; }
    public Theme theme()                   { return theme; }
    /**
     * Background auto-purge daemon (W3-B4). Non-null even when disabled —
     * check {@link AutoPurgeScheduler#isEnabled()} before assuming it runs.
     */
    public AutoPurgeScheduler autoPurgeScheduler() { return autoPurgeScheduler; }
    public EventSubmitter submitter()      { return this; }
    public long submitted()                { return submitted.get(); }
    public long gated()                    { return gated.get(); }

    /**
     * Path to the on-disk {@code config.json} the loader booted from.
     * May be {@code null} if the loader never called {@link #setConfigPath(Path)}
     * (e.g. tests that hand-construct via {@link #boot(GuardianConfig, Path, WorldMutator, OpLevelFallback, Executor, ThreadFactory)}
     * without going through a Guardian bootstrap).
     */
    public Path configPath()               { return configPath; }

    /**
     * Set the on-disk config path the engine can reload from later. Called by
     * each loader bootstrap immediately after {@link #boot} so that
     * {@code /vg reload} can find the file without cell-side plumbing.
     */
    public void setConfigPath(Path p)      { this.configPath = p; }

    // -------------------------------------------------------------------- reload

    /**
     * Result of a {@link #reloadConfig(Path)} call.
     *
     * <p>Immutable snapshot describing what the reload did / could not do:
     * <ul>
     *   <li>{@code hotSwapped}: names of config subsections whose new value took
     *       effect in-process (no restart required).</li>
     *   <li>{@code requiresRestart}: subsections that changed on disk but were
     *       <em>not</em> applied — restarting the server is required for them
     *       to take effect. Old in-memory values remain live.</li>
     *   <li>{@code errors}: fatal problems encountered (file missing, JSON
     *       malformed, validation failed, IO error). When non-empty, the live
     *       config is unchanged.</li>
     * </ul>
     *
     * <p>Mirrors CoreProtect's {@code /co reload} contract as documented in
     * {@code docs/COREPROTECT-COMPARISON.md} &sect; 1.2.
     */
    public record ReloadResult(List<String> hotSwapped,
                               List<String> requiresRestart,
                               List<String> errors) {
        public ReloadResult {
            hotSwapped = List.copyOf(hotSwapped);
            requiresRestart = List.copyOf(requiresRestart);
            errors = List.copyOf(errors);
        }
    }

    /**
     * Re-read the on-disk config file, hot-swap in-flight all subsections that
     * are safe to swap, and report anything that changed but would require a
     * server restart to take effect.
     *
     * <p><b>Hot-swap safe</b> (applied in-process):
     * {@code actions.*} (toggles + blacklists + coalescer knobs + entity
     * allowlist), {@code logFile.enabled}, {@code lookup.defaultPageSize} and
     * {@code lookup.maxResultRows}, {@code privacy.hashIps},
     * {@code purge.*} floors, {@code theme}.
     *
     * <p><b>Restart required</b> (change detected but old value stays live):
     * {@code database.*}, {@code queue.maxSize / flushIntervalMs / batchSize},
     * {@code logFile.directory / gzipRotated / retentionDays},
     * {@code permissions.*}, {@code lookup.maxConcurrent}, {@code privacy.salt}.
     *
     * @param path config.json location; if {@code null}, uses {@link #configPath()}
     * @return a summary of what changed and how — never {@code null}
     */
    public ReloadResult reloadConfig(Path path) {
        List<String> hot = new ArrayList<>();
        List<String> restart = new ArrayList<>();
        List<String> errs = new ArrayList<>();

        Path p = path != null ? path : configPath;
        if (p == null && dataDir != null) {
            // Convention shared by every loader bootstrap (Fabric / Forge / NeoForge)
            // — falling back to it means /vg reload works out of the box even if
            // the loader forgot to call setConfigPath(). Matches ForgeBootstrap etc.
            p = dataDir.resolve("config").resolve("vonixguardian").resolve("config.json");
        }
        if (p == null) {
            errs.add("no config path known (setConfigPath was never called and no dataDir bound)");
            return new ReloadResult(hot, restart, errs);
        }

        GuardianConfig fresh;
        try {
            fresh = ConfigLoader.load(p);
        } catch (Exception e) {
            errs.add("load failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            LOG.warn(MARKER, "Config reload failed for {}", p, e);
            return new ReloadResult(hot, restart, errs);
        }

        GuardianConfig old = this.config;

        // ---- restart-required diffs ----
        if (!Objects.equals(old.database(), fresh.database())) restart.add("database");
        if (!Objects.equals(old.queue(), fresh.queue())) restart.add("queue");
        if (old.logFile() != null && fresh.logFile() != null) {
            if (!Objects.equals(old.logFile().directory(), fresh.logFile().directory())
                || old.logFile().gzipRotated() != fresh.logFile().gzipRotated()
                || old.logFile().retentionDays() != fresh.logFile().retentionDays()) {
                restart.add("logFile.directory/gzipRotated/retentionDays");
            }
        }
        if (!Objects.equals(old.permissions(), fresh.permissions())) restart.add("permissions");
        if (old.lookup().maxConcurrent() != fresh.lookup().maxConcurrent()) restart.add("lookup.maxConcurrent");
        if (!Objects.equals(old.privacy().salt(), fresh.privacy().salt())) restart.add("privacy.salt");

        // ---- hot-swap: assemble a merged config that keeps the restart-required
        //      subsections from the OLD instance (they weren't applied) and takes
        //      everything else from FRESH. Then atomically swap the volatile ref
        //      and rebuild dependent state.
        GuardianConfig merged = new GuardianConfig(
            old.database(),   // restart-required — keep old
            old.queue(),      // restart-required — keep old
            new GuardianConfig.LogFile(
                fresh.logFile().enabled(),          // HOT
                old.logFile().directory(),          // restart
                old.logFile().gzipRotated(),        // restart
                old.logFile().retentionDays()       // restart
            ),
            fresh.actions(),                        // HOT (whole subsection)
            old.permissions(),                      // restart-required
            new GuardianConfig.Lookup(
                fresh.lookup().defaultPageSize(),   // HOT
                fresh.lookup().maxRadius(),         // HOT
                fresh.lookup().maxResultRows(),     // HOT
                old.lookup().maxConcurrent()        // restart
            ),
            new GuardianConfig.Privacy(
                fresh.privacy().hashIps(),          // HOT
                old.privacy().salt()                // restart
            ),
            fresh.purge(),                          // HOT
            fresh.theme()                           // HOT
        );

        // Track what actually changed to report as "hot-swapped".
        if (!Objects.equals(old.actions(), fresh.actions())) hot.add("actions");
        if (old.logFile().enabled() != fresh.logFile().enabled()) hot.add("logFile.enabled");
        if (old.lookup().defaultPageSize() != fresh.lookup().defaultPageSize()
            || old.lookup().maxRadius() != fresh.lookup().maxRadius()
            || old.lookup().maxResultRows() != fresh.lookup().maxResultRows()) {
            hot.add("lookup.defaultPageSize/maxRadius/maxResultRows");
        }
        if (old.privacy().hashIps() != fresh.privacy().hashIps()) hot.add("privacy.hashIps");
        if (!Objects.equals(old.purge(), fresh.purge())) hot.add("purge");
        if (!Objects.equals(old.theme(), fresh.theme())) hot.add("theme");

        // Swap in.
        this.config = merged;
        this.gate = new EventGate(merged.actions());
        this.theme = ThemeRegistry.get(merged.theme());

        // logFile.enabled hot-swap: turn off (close + null the ref) or turn on
        // (build a new JsonLinesLogFile at the same directory).
        try {
            JsonLinesLogFile currentLf = logFileRef.get();
            boolean wantEnabled = merged.logFile().enabled();
            if (wantEnabled && currentLf == null && dataDir != null) {
                Path logDir = dataDir.resolve(merged.logFile().directory());
                JsonLinesLogFile fresh2 = new JsonLinesLogFile(
                        logDir,
                        merged.logFile().gzipRotated(),
                        merged.logFile().retentionDays(),
                        Clock.systemUTC());
                logFileRef.set(fresh2);
            } else if (!wantEnabled && currentLf != null) {
                logFileRef.set(null);
                try { currentLf.close(); } catch (Exception ignored) { /* best-effort */ }
            }
        } catch (Exception e) {
            errs.add("logFile hot-swap failed: " + e.getMessage());
            LOG.warn(MARKER, "logFile hot-swap failed", e);
        }

        LOG.info(MARKER, "Config reloaded from {} (hot={}, restart={}, errors={})",
                p, hot, restart, errs);
        return new ReloadResult(hot, restart, errs);
    }

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
        // Central vanilla-griefer whitelist gate (WAVE-AUDIT-1.1.5 A4).
        // The cell layer still runs its own check for now — this belt-and-braces
        // enforcement means any future submitter that forgets the check is still
        // guarded. When actorName is a "#mob:<ns>:<path>" sentinel we strip it
        // and require the registry key be in DEFAULT_ALLOWLIST or config.
        // Player-driven paths (actorName not a mob sentinel) are pass-through.
        String stripped = VanillaGrieferSet.stripMobPrefix(actorName);
        if (stripped != null) {
            if (!VanillaGrieferSet.shouldRecord(stripped,
                    config.actions().entityChangeAllowlist(),
                    config.actions().entityChangeLogAllEntities())) {
                gated.incrementAndGet();
                return;
            }
        }
        // Producer-side coalescing: HTTYD-class modpacks fire this event 100k+/sec
        // per active dragon. Same (actor, coord) events inside a 500ms window are
        // suppressed. Distinct actors OR distinct coords still log normally.
        if (entityBlockCoalescer != null) {
            String actorId = (actorUuid != null) ? actorUuid.toString()
                                                 : (actorName != null ? actorName : "");
            if (!entityBlockCoalescer.shouldLog(actorId, worldId, x, y, z)) {
                gated.incrementAndGet();
                return;
            }
        }
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

    // -------------------------------------------------------------------- inspector

    /**
     * Run a synchronous block-history lookup at the given world position and
     * return ready-to-print chat lines. Intended for the inspector-wand
     * left-click handler in each loader module.
     *
     * <p>Returned strings are plain text because {@code core} has no Minecraft
     * types; the caller wraps each line in its loader's {@code Component} and
     * dispatches to the player. The first element is a header; remaining
     * elements are one per matching action (most recent first), or a single
     * "no history" line when nothing is found.
     *
     * <p>This call performs a JDBC query and MUST NOT be invoked from the
     * server thread — schedule it onto a worker (e.g. the loader's IO pool).
     *
     * @param worldId loader-resolved world key (e.g. {@code minecraft:overworld})
     * @param x       block X
     * @param y       block Y
     * @param z       block Z
     * @param limit   max history rows to return (clamped to {@code >=1})
     * @param now     reference epoch millis for "ago" rendering (typically
     *                {@code System.currentTimeMillis()})
     * @return ordered list of chat lines; never {@code null}, never empty
     * @throws Exception on DAO failure
     */
    public List<String> lookupAtPos(String worldId, int x, int y, int z,
                                    int limit, long now) throws Exception {
        return InspectorLookup.lookup(dao, worldId, x, y, z, limit, now);
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
            if (autoPurgeScheduler != null) {
                autoPurgeScheduler.shutdown(5_000L);
            }
        } catch (Exception e) {
            LOG.warn(MARKER, "AutoPurgeScheduler shutdown raised", e);
        }
        try {
            queue.drainAndFlush(30_000L);
        } catch (Exception e) {
            LOG.warn(MARKER, "Queue drain raised", e);
        }
        try {
            JsonLinesLogFile lf = logFileRef.get();
            if (lf != null) {
                lf.close();
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
