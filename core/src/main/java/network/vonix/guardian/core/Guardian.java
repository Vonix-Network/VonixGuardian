/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.api.GuardianAPI;
import network.vonix.guardian.core.api.VonixGuardianAPI;
import network.vonix.guardian.core.blacklist.BlacklistFile;
import network.vonix.guardian.core.blacklist.BlacklistMatcher;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.PerWorldConfigStore;
import network.vonix.guardian.core.event.BlacklistFileHook;
import network.vonix.guardian.core.event.EventGate;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.PerWorldEventHook;
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
    /**
     * v1.3.1 X3: short-term memory used to attribute fluid-flow rows produced
     * by the {@code LiquidBlockMixin} pipeline back to the player whose
     * bucket-empty seeded the source. Non-null; loader mixin bridges read this
     * via {@link #fluidSourceMemory()}.
     */
    private final network.vonix.guardian.core.attribution.FluidSourceMemory fluidSourceMemory;
    /**
     * v1.3.0 W3: shared off-thread joiner for explosion affected-block lists.
     * Loader-side event handlers hand pre-captured pos/id arrays here and the
     * per-affected-block {@link StringBuilder} join + queue enqueue moves off
     * the server thread. See {@link network.vonix.guardian.core.event.ExplosionJoinWorker}.
     */
    private final network.vonix.guardian.core.event.ExplosionJoinWorker explosionJoinWorker;
    /**
     * v1.3.1 X7: shared 5-min-TTL cache mapping (world, TNT position) →
     * priming actor. Populated by loader-side TntBlockMixin /
     * PrimedTntEntityMixin, consumed by the explosion-detonate handler via
     * {@link network.vonix.guardian.core.attribution.UniversalAttribution#resolveTntPrime}
     * to close CoreProtect-parity gap G-CP-2.
     */
    private final network.vonix.guardian.core.attribution.TntPrimeMemory tntPrimeMemory;
    /** Latched at boot when logFile.enabled; hot-swap of enabled flag flips this AtomicReference. */
    private final AtomicReference<JsonLinesLogFile> logFileRef;
    /** Server data-dir root, kept so reload can rebuild a JsonLinesLogFile at the same relative path. */
    private final Path dataDir;
    /** Path to the on-disk config.json; kept so /vg reload can re-read it without any cell-side plumbing. */
    private volatile Path configPath;
    private final AutoPurgeScheduler autoPurgeScheduler;
    /** Per-world config override cache (W3-B5); {@code null} when the {@code worlds/} dir doesn't exist. */
    private volatile PerWorldConfigStore perWorldStore;

    /** Latched on first {@link #api()} call. */
    private final AtomicReference<GuardianAPI> apiRef = new AtomicReference<>();

    /** Currently-live blacklist.txt hook, or {@code null} if the file is absent. */
    private volatile BlacklistFileHook blacklistHook;

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
        this.fluidSourceMemory = new network.vonix.guardian.core.attribution.FluidSourceMemory();
        this.explosionJoinWorker = new network.vonix.guardian.core.event.ExplosionJoinWorker();
        this.tntPrimeMemory = new network.vonix.guardian.core.attribution.TntPrimeMemory();
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

        LOG.info(MARKER, "Booting VonixGuardian v{} (db={}, theme={}, queue.max={})",
                GuardianAPI.PLUGIN_VERSION,
                config.database().type(), config.theme(), config.queue().maxSize());

        GuardianDao dao = StorageFactory.open(config);
        dao.init();

        JsonLinesLogFile logFile = null;
        if (config.logFile().enabled()) {
            Path logDir = dataDir.resolve(config.logFile().directory());
            logFile = new JsonLinesLogFile(logDir,
                    config.logFile().gzipRotated(),
                    config.logFile().retentionDays(),
                    config.logFile().forceSyncOnFlush(),
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
        PermissionResolver perms = new PermissionResolver(config.permissions(), opLookup);
        RollbackEngine rollback = new RollbackEngine(
                dao, mutator, mainThreadExec,
                // v1.3.2 Y3, P1-1 close-out: wire the X8 supplemental-scan reach
                // knob into the engine at boot. Prior to Y3 this call used the
                // 3-arg constructor and hard-coded MAX_TNT_REACH=16, so operators
                // editing rollback.explosionSupplementalReach in config.json got
                // no effect even though ConfigLoader passed validation.
                config.rollback().explosionSupplementalReach());
        PurgeEngine purgeEng = new PurgeEngine(dao);
        try {
            rollback.recoverIncompleteBatches();
        } catch (Exception e) {
            LOG.warn(MARKER, "Recovery scan failed (non-fatal)", e);
        }
        UndoStack undo = new UndoStack(20);
        Theme theme = ThemeRegistry.get(config.theme());
        network.vonix.guardian.core.i18n.Messages.setLanguage(config.language());

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
        Guardian g = new Guardian(config, dao, queue, logHolder, gate, perms, rollback, purgeEng, undo, theme, coal, dataDir, autoPurge);

        // W3-B5: per-world config overrides (CoreProtect world_nether.yml shadow pattern).
        // Only wire when the operator has actually created a worlds/ dir — no silent surprise
        // registration on fresh installs.
        Path worldsDir = dataDir.resolve("config").resolve("vonixguardian").resolve("worlds");
        if (java.nio.file.Files.isDirectory(worldsDir)) {
            PerWorldConfigStore store = new PerWorldConfigStore(config.actions());
            store.reload(worldsDir);
            gate.addHook(new PerWorldEventHook(store, config.actions()));
            g.perWorldStore = store;
            LOG.info(MARKER, "Per-world overrides loaded: {} world(s) — {}",
                    store.overriddenWorlds().size(), store.overriddenWorlds());
        }

        // ---- W3-B6: load blacklist.txt (if present) and register as an EventHook ----
        try {
            Path blPath = dataDir.resolve("blacklist.txt");
            BlacklistFile.Parsed parsed = BlacklistFile.load(blPath);
            if (!parsed.equals(BlacklistFile.Parsed.empty())) {
                BlacklistMatcher matcher = new BlacklistMatcher(parsed);
                BlacklistFileHook hook = new BlacklistFileHook(matcher);
                gate.addHook(hook);
                g.blacklistHook = hook;
                LOG.info(MARKER, "blacklist.txt loaded ({} rules)", matcher.size());
            }
        } catch (Exception e) {
            LOG.warn(MARKER, "blacklist.txt load failed (non-fatal)", e);
        }
        // W3-B11: public cancellable pre-log hook. Registered LAST so that
        // cheaper built-in hooks (per-world overrides — B5, blacklist.txt — B6)
        // get first crack before we pay a native-bus dispatch.
        gate.addHook(new network.vonix.guardian.core.event.PreLogEventHook());
        return g;
    }

    // -------------------------------------------------------------------- getters

    public GuardianConfig config()         { return config; }
    public GuardianDao dao()               { return dao; }
    public BatchedAsyncWriteQueue queue()  { return queue; }
    /** Live {@link EventGate} the hook chain is registered on. Diagnostics only. */
    public EventGate gate()                { return gate; }
    /** Live coalescer, or {@code null} when disabled by config. Diagnostics only. */
    public EntityBlockChangeCoalescer entityBlockCoalescer() { return entityBlockCoalescer; }
    /**
     * v1.3.0 W3: off-thread joiner for {@code EXPLOSION} affected-block lists.
     * Loader-side event handlers call {@code explosionJoinWorker().submit(...)}
     * with pre-captured pos/id arrays; the join + queue enqueue happens off
     * the server thread. Never {@code null}.
     */
    public network.vonix.guardian.core.event.ExplosionJoinWorker explosionJoinWorker() {
        return explosionJoinWorker;
    }
    /**
     * v1.3.1 X3: short-term bucket-empty → fluid-spread traceback memory.
     * Loader mixin bridges use this from both the bucket-use TAIL inject
     * ({@link network.vonix.guardian.core.attribution.FluidSourceMemory#recordBucketEmpty})
     * and the {@code LiquidBlockMixin} spread inject
     * ({@link network.vonix.guardian.core.attribution.FluidSourceMemory#lookup}).
     * Never {@code null}.
     */
    public network.vonix.guardian.core.attribution.FluidSourceMemory fluidSourceMemory() {
        return fluidSourceMemory;
    }

    /**
     * X7: shared TNT-prime memory. Loader-side {@code TntBlockMixin} /
     * {@code PrimedTntEntityMixin} record actor identities here at prime time;
     * the {@code ExplosionEvent.Detonate} handler consults it via
     * {@link network.vonix.guardian.core.attribution.UniversalAttribution#resolveTntPrime}
     * before falling back to the sentinel.
     */
    public network.vonix.guardian.core.attribution.TntPrimeMemory tntPrimeMemory() {
        return tntPrimeMemory;
    }
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
    /**
     * Per-world config override store (W3-B5). Returns {@code null} when the
     * {@code config/vonixguardian/worlds/} dir did not exist at boot AND has
     * never been created via {@code /vg reload}.
     */
    public PerWorldConfigStore perWorldStore() { return perWorldStore; }
    /**
     * Live {@code blacklist.txt} hook, or {@code null} if the file is missing.
     * Diagnostics and tests only; the hook is already registered on the
     * {@link EventGate} chain.
     * @since 1.1.7 (W3-B6)
     */
    public BlacklistFileHook blacklistHook()       { return blacklistHook; }
    public EventSubmitter submitter()      { return this; }

    /**
     * Public, version-stable Java API surface (W3-B12+B13). The default impl
     * ({@link GuardianAPI}) is constructed lazily on first access and cached
     * for the lifetime of this Guardian. Consumer mods should obtain this via
     * reflection (see {@code docs/API.md} § "Public Java API (v1)") to keep
     * VG a soft dependency.
     *
     * @return live API handle; never {@code null}
     */
    public VonixGuardianAPI api() {
        GuardianAPI cur = apiRef.get();
        if (cur == null) {
            GuardianAPI fresh = new GuardianAPI(this);
            if (apiRef.compareAndSet(null, fresh)) {
                cur = fresh;
            } else {
                cur = apiRef.get();
            }
        }
        return cur;
    }

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
        // v1.3.7 DD1 (round-7 P1): serialize the entire reload critical section.
        // Pre-v1.3.6, /vg config set called reloadConfig on the server thread; that
        // was serialized by the tick loop. v1.3.6 CC2 routed /vg config set onto
        // WORKER (pool size 2), so two config-set commands OR a config-set racing
        // /vg reload can now interleave the read-old / build-merged / publish-new
        // sequence and clobber each other's gate/config/perWorldStore/rollbackEngine
        // /autoPurgeScheduler updates. Guard the whole path under CONFIG_MUTATION_LOCK
        // (shared across cells' /vg config set path via {@link #withConfigMutationLock}).
        synchronized (CONFIG_MUTATION_LOCK) {
            return doReloadConfig(path);
        }
    }

    /**
     * v1.3.7 DD1: shared monitor guarding all config mutation paths. Loader-side
     * /vg config set implementations MUST hold this monitor while performing the
     * read-current-config / build-merged / persist-to-disk / reloadConfig sequence
     * to prevent lost updates when concurrent set commands race on WORKER.
     * <p>Cells use an explicit {@code synchronized (Guardian.CONFIG_MUTATION_LOCK)}
     * block around their critical section rather than a Runnable wrapper because
     * they need to short-circuit on validation failure with a chat error.</p>
     */
    public static final Object CONFIG_MUTATION_LOCK = new Object();

    /**
     * v1.3.7 DD1: reload variant for callers that already hold
     * {@link #CONFIG_MUTATION_LOCK}. Used by the /vg config set critical section
     * to avoid re-entering the monitor (Java monitors are reentrant so this
     * is technically safe today — but naming the boundary makes the intent
     * explicit and lets us swap to a non-reentrant Lock later without breaking
     * loader cells).
     */
    public ReloadResult reloadConfigUnlocked(Path path) {
        assert Thread.holdsLock(CONFIG_MUTATION_LOCK)
                : "reloadConfigUnlocked called without CONFIG_MUTATION_LOCK";
        return doReloadConfig(path);
    }

    private ReloadResult doReloadConfig(Path path) {
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
            // v1.3.1 X6 (P2-2): route ConfigLoader.load onto the common ForkJoinPool
            // so Files.readString + Gson.fromJson do not stall the server thread on
            // a slow / networked filesystem. reloadConfig is a synchronous API, so
            // we block waiting for the parse — but a hot ForkJoinPool worker doing
            // the IO keeps a soft-stall out of the tick when the caller thread is
            // the server thread. Timeout guards against a stuck NFS mount.
            final Path readPath = p;
            fresh = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return ConfigLoader.load(readPath);
                        } catch (java.io.IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    })
                    .get(10L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            errs.add("load timed out after 10s (slow filesystem?)");
            LOG.warn(MARKER, "Config reload timed out for {}", p);
            return new ReloadResult(hot, restart, errs);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            errs.add("load failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            LOG.warn(MARKER, "Config reload failed for {}", p, cause);
            return new ReloadResult(hot, restart, errs);
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
        //
        // v1.3.2 Y3 (P0-1 close-out): the previous form of this call used the
        // 9-arg backward-compat GuardianConfig(...) overload, which fills
        // {@code storage}, {@code rollback}, and {@code language} with defaults
        // — meaning /vg reload silently reverted storage.persistNbt=true to
        // false, rollback.explosionSupplementalReach to 16, and language to
        // "en_us". This now uses the canonical 12-arg form so every operator
        // knob survives reload.
        GuardianConfig merged = new GuardianConfig(
            old.database(),   // restart-required — keep old
            old.queue(),      // restart-required — keep old
            new GuardianConfig.LogFile(
                fresh.logFile().enabled(),          // HOT
                old.logFile().directory(),          // restart
                old.logFile().gzipRotated(),       // restart
                old.logFile().retentionDays(),     // restart
                fresh.logFile().forceSyncOnFlush()  // HOT (v1.3.1 X6 P3-4)
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
            fresh.purge(),                          // HOT (Y3: applyConfig on scheduler)
            fresh.storage(),                        // HOT (Y3: X1 persistNbt survives reload)
            fresh.rollback(),                       // HOT (Y3: X8 reach survives reload)
            fresh.theme(),                          // HOT
            fresh.language()                        // HOT
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
        // v1.3.2 Y3 (P0-1 + P2-5 close-out): storage.persistNbt, rollback.explosionSupplementalReach,
        // and language all take effect immediately — they were silently dropped
        // pre-Y3 (see 9-arg comment above) so the operator got no /vg reload feedback.
        if (old.storage().persistNbt() != fresh.storage().persistNbt()) {
            hot.add("storage.persistNbt");
        }
        if (old.rollback().explosionSupplementalReach() != fresh.rollback().explosionSupplementalReach()) {
            hot.add("rollback.explosionSupplementalReach");
        }
        if (!Objects.equals(old.language(), fresh.language())) hot.add("language");

        // Swap in.
        // v1.3.1 X6 (P2-5): build the fresh EventGate on a LOCAL reference and
        // register every hook against it before publishing to `this.gate`. Between
        // the pre-X6 `this.gate = new EventGate(...)` at the top and the last
        // `this.gate.addHook(...)` at the bottom, concurrent submitters saw a
        // freshly-empty gate — blacklist rules briefly did not apply, per-world
        // overrides briefly did not apply. Building locally means every submitter
        // sees either the fully old chain OR the fully new chain, never a
        // half-registered one.
        //
        // v1.3.3 Z4 (G-Y3-1 P2 close-out): `this.config = merged` MOVED to below the
        // `this.gate = localGate` publish (see end of this method). Rationale: submit()
        // reads `gate` on the hot path but `config` (for auxiliary fields like
        // storage.persistNbt / rollback.explosionSupplementalReach / language) on
        // cooler paths. Publishing `config` first briefly left submitters observing
        // the NEW config with the OLD gate — e.g. the new persistNbt flag was live
        // while the old event gate's hooks (blacklist/per-world) were still in
        // effect. Ordering: gate first keeps hooks in old state during the config
        // swap; there is no window in which a NEW hook could observe an OLD
        // persistNbt / language, since hooks are terminal (do not read config()).
        EventGate localGate = new EventGate(merged.actions());
        this.theme = ThemeRegistry.get(merged.theme());
        network.vonix.guardian.core.i18n.Messages.setLanguage(merged.language());

        // v1.3.2 Y3 (P1-1 + P2-1 close-out): hot-swap the supplemental EXPLOSION
        // scan reach on the existing RollbackEngine without rebuilding it. The
        // engine's field is volatile (Y3) so a concurrent /vg rollback observes
        // either the old or new value — no torn read, no half-swapped engine.
        try {
            if (this.rollbackEngine != null) {
                this.rollbackEngine.setExplosionSupplementalReach(
                    merged.rollback().explosionSupplementalReach());
            }
        } catch (Exception e) {
            errs.add("rollback.setExplosionSupplementalReach failed: " + e.getMessage());
            LOG.warn(MARKER, "rollback reach hot-swap failed", e);
        }

        // v1.3.2 Y3 (P2-4 close-out): hot-swap the auto-purge daemon's schedule.
        // Pre-Y3 the merged config carried the new autoPurgeTime/autoPurgeSeconds
        // but the running daemon kept its original schedule until server restart.
        try {
            if (this.autoPurgeScheduler != null
                && !Objects.equals(old.purge(), merged.purge())) {
                boolean changed = this.autoPurgeScheduler.applyConfig(merged);
                if (changed) {
                    // "purge" already recorded in `hot` above — no duplicate entry.
                }
            }
        } catch (Exception e) {
            errs.add("autoPurgeScheduler.applyConfig failed: " + e.getMessage());
            LOG.warn(MARKER, "auto-purge reload failed", e);
        }

        // W3-B5: per-world overrides. Point the store at the new root, re-scan the
        // worlds/ dir (files may have been added/removed), and re-register the hook
        // on the freshly-built gate. If no store was ever created (fresh install
        // without worlds/ dir) but the dir now exists, build one now.
        try {
            Path worldsDir = dataDir != null
                ? dataDir.resolve("config").resolve("vonixguardian").resolve("worlds")
                : null;
            PerWorldConfigStore store = this.perWorldStore;
            if (worldsDir != null && java.nio.file.Files.isDirectory(worldsDir)) {
                if (store == null) {
                    store = new PerWorldConfigStore(merged.actions());
                    this.perWorldStore = store;
                } else {
                    store.updateRoot(merged.actions());
                }
                java.util.Set<String> before = new java.util.HashSet<>(store.overriddenWorlds());
                store.reload(worldsDir);
                java.util.Set<String> after = store.overriddenWorlds();
                localGate.addHook(new PerWorldEventHook(store, merged.actions()));
                if (!before.equals(after)) {
                    java.util.Set<String> added = new java.util.HashSet<>(after);
                    added.removeAll(before);
                    java.util.Set<String> removed = new java.util.HashSet<>(before);
                    removed.removeAll(after);
                    hot.add("per-world: " + after.size() + " world(s) loaded"
                            + (added.isEmpty() ? "" : ", added=" + added)
                            + (removed.isEmpty() ? "" : ", removed=" + removed));
                } else if (!after.isEmpty()) {
                    hot.add("per-world: " + after.size() + " world(s) reloaded (" + after + ")");
                }
            } else if (store != null) {
                // worlds/ dir vanished — clear the cache. Hook stays registered but
                // now always returns PASS.
                store.updateRoot(merged.actions());
                boolean hadEntries = !store.overriddenWorlds().isEmpty();
                store.reload(worldsDir);
                localGate.addHook(new PerWorldEventHook(store, merged.actions()));
                if (hadEntries) hot.add("per-world: cleared (worlds/ dir gone)");
            }
        } catch (Exception e) {
            errs.add("per-world reload failed: " + e.getMessage());
            LOG.warn(MARKER, "per-world reload failed", e);
        }

        // W3-B6: reload blacklist.txt. Re-parse from disk; if present, build a
        // fresh matcher and register a new hook on the new gate. Report rule
        // count under "blacklist.txt" in the hot-swapped list.
        BlacklistFileHook newBlacklistHook = null;
        try {
            Path blPath = (dataDir != null) ? dataDir.resolve("blacklist.txt") : null;
            BlacklistFile.Parsed parsed = (blPath != null)
                    ? BlacklistFile.load(blPath) : BlacklistFile.Parsed.empty();
            int oldCount = (this.blacklistHook == null) ? 0 : this.blacklistHook.matcher().size();
            if (parsed.size() > 0) {
                BlacklistMatcher matcher = new BlacklistMatcher(parsed);
                BlacklistFileHook hook = new BlacklistFileHook(matcher);
                localGate.addHook(hook);
                newBlacklistHook = hook;
                if (matcher.size() != oldCount) {
                    hot.add("blacklist.txt (" + matcher.size() + " rules)");
                }
            } else {
                if (oldCount > 0) {
                    hot.add("blacklist.txt (cleared)");
                }
            }
        } catch (Exception e) {
            errs.add("blacklist.txt reload failed: " + e.getMessage());
            LOG.warn(MARKER, "blacklist.txt reload failed", e);
        }

        // W3-B11: preserve the public cancellable pre-log hook on the rebuilt
        // EventGate. Keep it terminal so cheaper per-world/blacklist hooks run
        // before native event-bus dispatches after /vg reload just like at boot.
        localGate.addHook(new network.vonix.guardian.core.event.PreLogEventHook());

        // v1.3.1 X6 (P2-5): atomic publication. Every hook is registered on
        // localGate; only now do we flip `this.gate` and `this.blacklistHook`.
        // Any submitter racing this method observes either the old fully-populated
        // gate or the new fully-populated gate.
        //
        // v1.3.3 Z4 (G-Y3-1 P2 close-out): publish `this.gate` BEFORE `this.config`.
        // A submitter observes the gate (hot path) before it observes the new config
        // (cool path). Reversing the pair (config-then-gate) would let a submitter
        // read the new persistNbt/language while still routing through the old
        // hook chain — a brief false-positive NBT capture window on any submit
        // that races the reload. Ordered this way, hooks flip atomically to the
        // new set and then config swaps under them; hooks are terminal (they do
        // not read config()) so an in-flight decision under the new gate is
        // unaffected by the imminent config swap.
        this.gate = localGate;
        this.config = merged;
        this.blacklistHook = newBlacklistHook;

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
                        merged.logFile().forceSyncOnFlush(),
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

    /**
     * Per-server-thread scratch {@link ActionBuilder} used by {@link #seed} on the hot path.
     *
     * <p>v1.3.0 W2 allocation cut: at hot rates (piston farm, fire spread, entity spam,
     * hoppers) the old {@code new ActionBuilder()} in {@code seed()} was allocating a fresh
     * 16-field mutable builder + object header per event on the server thread — MB/s of
     * short-lived garbage feeding GC pressure. This {@code ThreadLocal} keeps one builder
     * per producer thread and calls {@link ActionBuilder#reset()} to hand it back cleared.
     * Immutable {@link Action} objects produced by {@link ActionBuilder#build()} are still
     * fresh per submit (the DAO holds the reference in a batch); we only recycle the
     * mutable scratch, which is the amortizable half of the pair.</p>
     *
     * <p>Tradeoff: Each unique producer thread parks its {@code ActionBuilder} for the
     * lifetime of the thread; on a Minecraft server that means one instance for the server
     * thread plus at most one per worker on any thread pool that routes into
     * {@link EventSubmitter}. In practice: server thread + optional AI-executor + a handful
     * of async producers. The steady-state memory cost is 8 * &lt; 200 bytes.</p>
     */
    private static final ThreadLocal<ActionBuilder> SCRATCH_BUILDER =
            ThreadLocal.withInitial(ActionBuilder::new);

    /**
     * Package-visible accessor for regression tests that need to assert the scratch builder
     * is stable per thread (see {@code ActionBuilderPoolingTest}). Not part of the public
     * API surface.
     */
    static ActionBuilder scratchBuilderForCurrentThread() {
        return SCRATCH_BUILDER.get();
    }

    private ActionBuilder seed(ActionType type, UUID actorUuid, String actorName, String worldId) {
        return SCRATCH_BUILDER.get().reset()
                .type(type)
                .actorUuid(actorUuid)
                .actorName(orUnknown(actorName))
                .worldId(worldId);
    }

    @Override
    public void submit(Action a) {
        submitAccepted(a);
    }

    /**
     * Submit an action and report whether it passed the gate and reached the
     * bounded write queue. EventSubmitter's historical void method delegates
     * here; public API direct-log calls use the boolean for their contract.
     */
    public boolean submitAccepted(Action a) {
        if (a == null) {
            return false;
        }
        // v1.3.0 W2: mixin hot-event kill-switch is now folded into EventGate.shouldLog
        // (see EventGate.mixinHotEventsEnabled + MixinHotEventFilter). Keeps Guardian.submit
        // free of gate-adjacent policy checks and lets the gate short-circuit before any
        // hook chain traversal work.
        if (!gate.shouldLog(a)) {
            gated.incrementAndGet();
            return false;
        }
        submitted.incrementAndGet();
        return queue.submit(a);
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
        submitExplosion(actorUuid, actorName, worldId, x, y, z, affectedJoined, sourceTag, null);
    }

    @Override
    public void submitExplosion(UUID actorUuid, String actorName, String worldId,
                                int x, int y, int z, String affectedJoined, String sourceTag,
                                byte[] blockEntityNbtSidecar) {
        // v1.3.1 X6 (P3-2): use the per-thread scratch builder via seed() instead of
        // allocating a fresh ActionBuilder. Consistent with every other submit* path
        // and cuts ~40B/allocation on the explosion-join worker thread on TNT-heavy
        // servers. actorName defaults to Sentinel.EXPLOSION when null (kept identical
        // to pre-X6 semantics — do NOT change to Sentinel.UNKNOWN).
        submit(seed(ActionType.EXPLOSION, actorUuid,
                    actorName != null ? actorName : Sentinel.EXPLOSION,
                    worldId)
                .position(x, y, z)
                .targetId(affectedJoined)
                .sourceTag(sourceTag)
                .blockEntityNbt(blockEntityNbtSidecar)
                .build());
    }

    @Override
    public void submitPortalCreate(UUID actorUuid, String actorName, String worldId,
                                   int x, int y, int z, String blockId, String sourceTag) {
        submit(seed(ActionType.PORTAL_CREATE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag).build());
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
    public void submitSign(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String joinedLines,
                           String side, String dyeColor, Boolean waxed) {
        submit(seed(ActionType.SIGN, actorUuid, actorName, worldId)
                .position(x, y, z)
                .targetId(joinedLines)
                .signSide(side)
                .signDyeColor(dyeColor)
                .signWaxed(waxed)
                .build());
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
    public void submitFluidFlow(UUID actorUuid, String actorName, String worldId,
                                int x, int y, int z, String fluidBlockId, String sourceTag) {
        submit(seed(ActionType.FLUID_FLOW, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(fluidBlockId).sourceTag(sourceTag).build());
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
        // Central vanilla-griefer whitelist gate (WAVE-AUDIT-1.1.5 A4, v1.2.5 attribution hardening).
        // Loader resolvers may legitimately credit a mob-caused block change to a
        // player owner/rider/tamer via actorUuid+actorName. That attribution is
        // valuable for lookup, but it must NOT bypass the modded-entity flood
        // policy: the entity that physically changed the block is carried in
        // sourceTag as "#mob:<ns>:<path>" and is checked here as well.
        String entityRegistryKey = VanillaGrieferSet.stripMobPrefix(actorName);
        if (entityRegistryKey == null) {
            entityRegistryKey = VanillaGrieferSet.stripMobPrefix(sourceTag);
        }
        if (entityRegistryKey != null) {
            if (!VanillaGrieferSet.shouldRecord(entityRegistryKey,
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
        // RollbackEngine contract: targetId is the pre-change block, targetMeta
        // is the post-change block. This keeps EventGate's block blacklist keyed
        // to the affected original block (not the frequent minecraft:air result),
        // while still giving restore enough data to re-apply the mutation.
        submit(seed(ActionType.ENTITY_CHANGE_BLOCK, actorUuid, actorName, worldId)
                .position(x, y, z)
                .targetId(oldBlockId != null ? oldBlockId : "minecraft:air")
                .targetMeta(newBlockId != null ? newBlockId : "minecraft:air")
                .sourceTag(sourceTag)
                .build());
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

    // -------------------------------------------------------------------- v1.3.1 X1: NBT overrides
    //
    // These honor storage.persistNbt: when disabled (default), we ignore the
    // NBT payload entirely and behave as the non-NBT overload — no allocation,
    // no wasted DAO column writes. When enabled, we thread the NBT bytes into
    // the ActionBuilder so the DAO persists them to the v5 columns.
    //
    // We keep the surface small (block break/place, container change, item
    // drop/pickup, entity kill/spawn, entity change block, hopper push/pull);
    // sibling waves X4/X7/X9 add more producer wiring on top.

    private boolean persistNbt() {
        GuardianConfig cfg = config;
        return cfg != null && cfg.storage() != null && cfg.storage().persistNbt();
    }

    @Override
    public void submitBlockBreak(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String blockId, String sourceTag,
                                 String oldBlockState, byte[] blockEntityNbt) {
        if (!persistNbt()) {
            submitBlockBreak(actorUuid, actorName, worldId, x, y, z, blockId, sourceTag);
            return;
        }
        submit(seed(ActionType.BLOCK_BREAK, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag)
                .oldBlockState(oldBlockState)
                .blockEntityNbt(blockEntityNbt)
                .build());
    }

    @Override
    public void submitBlockPlace(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String blockId, String sourceTag,
                                 String newBlockState, byte[] blockEntityNbt) {
        if (!persistNbt()) {
            submitBlockPlace(actorUuid, actorName, worldId, x, y, z, blockId, sourceTag);
            return;
        }
        submit(seed(ActionType.BLOCK_PLACE, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(blockId).sourceTag(sourceTag)
                .newBlockState(newBlockState)
                .blockEntityNbt(blockEntityNbt)
                .build());
    }

    @Override
    public void submitContainerChange(UUID actorUuid, String actorName, String worldId,
                                      int x, int y, int z, String itemId, int delta, String sourceTag,
                                      byte[] itemNbt) {
        if (delta == 0) {
            return;
        }
        if (!persistNbt()) {
            submitContainerChange(actorUuid, actorName, worldId, x, y, z, itemId, delta, sourceTag);
            return;
        }
        submit(seed(delta > 0 ? ActionType.CONTAINER_DEPOSIT : ActionType.CONTAINER_WITHDRAW,
                    actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(Math.abs(delta)).sourceTag(sourceTag)
                .itemNbt(itemNbt)
                .build());
    }

    @Override
    public void submitItemDrop(UUID actorUuid, String actorName, String worldId,
                               int x, int y, int z, String itemId, int amount, String sourceTag,
                               byte[] itemNbt) {
        if (!persistNbt()) {
            submitItemDrop(actorUuid, actorName, worldId, x, y, z, itemId, amount, sourceTag);
            return;
        }
        submit(seed(ActionType.ITEM_DROP, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag)
                .itemNbt(itemNbt)
                .build());
    }

    @Override
    public void submitItemPickup(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag,
                                 byte[] itemNbt) {
        if (!persistNbt()) {
            submitItemPickup(actorUuid, actorName, worldId, x, y, z, itemId, amount, sourceTag);
            return;
        }
        submit(seed(ActionType.ITEM_PICKUP, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag)
                .itemNbt(itemNbt)
                .build());
    }

    @Override
    public void submitEntityKill(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String entityType, String sourceTag,
                                 byte[] entityNbt) {
        if (!persistNbt()) {
            submitEntityKill(actorUuid, actorName, worldId, x, y, z, entityType, sourceTag);
            return;
        }
        submit(seed(ActionType.ENTITY_KILL, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag)
                .entityNbt(entityNbt)
                .build());
    }

    @Override
    public void submitEntitySpawn(UUID actorUuid, String actorName, String worldId,
                                  int x, int y, int z, String entityType, String sourceTag,
                                  byte[] entityNbt) {
        if (!persistNbt()) {
            submitEntitySpawn(actorUuid, actorName, worldId, x, y, z, entityType, sourceTag);
            return;
        }
        submit(seed(ActionType.ENTITY_SPAWN, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(entityType).sourceTag(sourceTag)
                .entityNbt(entityNbt)
                .build());
    }

    @Override
    public void submitEntityChangeBlock(UUID actorUuid, String actorName, String worldId,
                                        int x, int y, int z,
                                        String oldBlockId, String newBlockId, String sourceTag,
                                        String oldBlockState, String newBlockState,
                                        byte[] blockEntityNbt) {
        if (!persistNbt()) {
            submitEntityChangeBlock(actorUuid, actorName, worldId, x, y, z,
                                    oldBlockId, newBlockId, sourceTag);
            return;
        }
        // The non-NBT overload calls submitEntityChangeBlock at line 881 with
        // its coalescer plumbing; we keep the payload-carrying variant separate
        // and route straight through submit(). Sibling wave X2 owns the
        // coalescer-friendly NBT path.
        submit(seed(ActionType.ENTITY_CHANGE_BLOCK, actorUuid, actorName, worldId)
                .position(x, y, z)
                .targetId(oldBlockId != null ? oldBlockId : "minecraft:air")
                .targetMeta(newBlockId != null ? newBlockId : "minecraft:air")
                .sourceTag(sourceTag)
                .oldBlockState(oldBlockState)
                .newBlockState(newBlockState)
                .blockEntityNbt(blockEntityNbt)
                .build());
    }

    @Override
    public void submitHopperPush(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag,
                                 byte[] itemNbt) {
        if (!persistNbt()) {
            submitHopperPush(actorUuid, actorName, worldId, x, y, z, itemId, amount, sourceTag);
            return;
        }
        submit(seed(ActionType.HOPPER_PUSH, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag)
                .itemNbt(itemNbt)
                .build());
    }

    @Override
    public void submitHopperPull(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag,
                                 byte[] itemNbt) {
        if (!persistNbt()) {
            submitHopperPull(actorUuid, actorName, worldId, x, y, z, itemId, amount, sourceTag);
            return;
        }
        submit(seed(ActionType.HOPPER_PULL, actorUuid, actorName, worldId)
                .position(x, y, z).targetId(itemId).amount(amount).sourceTag(sourceTag)
                .itemNbt(itemNbt)
                .build());
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
        // v1.3.1 X6 (P2-1): drain the ExplosionJoinWorker BEFORE queue.drainAndFlush.
        // Any join task queued at t=stop-Δ needs to land its submitExplosion call
        // into the write queue before we tell the queue to drain — otherwise the
        // row races the queue close and the DAO close, and gets silently dropped.
        // close() now blocks up to 5s waiting for pending join tasks to finish.
        try {
            explosionJoinWorker.close();
        } catch (Exception e) {
            LOG.warn(MARKER, "ExplosionJoinWorker close raised", e);
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
        try {
            tntPrimeMemory.clear();
        } catch (Exception e) {
            LOG.warn(MARKER, "TntPrimeMemory clear raised", e);
        }
        LOG.info(MARKER, "VonixGuardian offline.");
    }

    @Override
    public void close() {
        shutdown();
    }
}
