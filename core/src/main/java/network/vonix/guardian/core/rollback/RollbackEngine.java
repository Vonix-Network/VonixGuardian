package network.vonix.guardian.core.rollback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Coordinates rollback and restore of logged actions.
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Query the {@link GuardianDao} (caller thread — caller is a worker, not
 *       the server thread, per the DAO contract). The filter carries an
 *       SQL-side {@code rolledBack} predicate so we don't pull rows we'll
 *       throw away.</li>
 *   <li>Build a {@link RollbackPlan} that deduplicates by position and orders
 *       newest-first.</li>
 *   <li>Open a {@code vg_rollback_batches} audit record so a server crash
 *       mid-dispatch leaves recoverable state.</li>
 *   <li>If not a preview, submit the world mutations to the
 *       {@code mainThreadExecutor} in batches of {@link #BATCH_SIZE}.</li>
 *   <li>Wait for each batch's completion outcome, mark only confirmed applied
 *       IDs in the DAO ({@code rolled_back=1} for rollback,
 *       {@code rolled_back=0} for restore), then close the batch record.</li>
 * </ol>
 *
 * <p>The actual world mutations are delegated to a {@link WorldMutator}
 * supplied by the loader module. The engine is otherwise loader-agnostic.</p>
 */
public final class RollbackEngine {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackEngine.class);

    /** Default max mutations dispatched per server tick. */
    public static final int BATCH_SIZE = 1000;

    /** Page size for {@link #fetchMatches}. */
    static final int PAGE_SIZE = 5000;

    /** Inert block id used to clear a {@code BLOCK_PLACE}. */
    static final String AIR = "minecraft:air";

    /**
     * Registry id of a dropped item entity. Rolling back an {@code ITEM_DROP}
     * means removing that entity, and {@code tryRemoveEntity} matches the type
     * strictly so a mob or player standing on the recorded block is never
     * discarded by mistake.
     */
    static final String ITEM_ENTITY_TYPE = "minecraft:item";

    /**
     * One audit row expanded to many block mutations, and only some of them
     * landed. The world is neither untouched nor fully restored, so the caller
     * must persist {@link WorldMutationResult.Status#REPAIR_REQUIRED} instead of
     * reporting a plain failure — the operator needs to know blocks did change.
     *
     * <p>Declared here rather than as its own top-level type so the rollback
     * package surface stays small: it is only ever thrown and caught by this
     * engine.</p>
     */
    static final class PartialExplosionMutationException extends RuntimeException {
        private final int applied;
        private final int total;

        PartialExplosionMutationException(String message, int applied, int total) {
            super(message);
            this.applied = applied;
            this.total = total;
        }

        /** Variant that preserves the originating mutator failure as the cause. */
        PartialExplosionMutationException(String message, int applied, int total, Throwable cause) {
            super(message, cause);
            this.applied = applied;
            this.total = total;
        }

        /** Affected blocks written before the failure. */
        int applied() {
            return applied;
        }

        /** Affected blocks the row describes. */
        int total() {
            return total;
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Default reach (block-radius) padding for the W5 supplemental EXPLOSION scan.
     * Vanilla TNT clears roughly a 7-block radius; 16 gives comfortable headroom
     * without unbounding the scan. Overridable via
     * {@link network.vonix.guardian.core.config.GuardianConfig.Rollback#explosionSupplementalReach}.
     * @since 1.3.1 X8
     */
    public static final int MAX_TNT_REACH = 16;

    /**
     * Share of an explosion row's blocks that may fail to restore before the row is
     * escalated to repair-required.
     *
     * <p>A handful of positions in a blast are routinely unplaceable — another mod
     * claimed them, a player refilled them, a fluid flowed back. Treating any single
     * miss as a failure meant a rollback of a large griefing incident could never
     * report success: every blast row became REPAIR_REQUIRED and the batch stayed
     * open. Escalate only when a real share of the row is unaccounted for.</p>
     */
    static final int SEVERE_EXPLOSION_FAILURE_PERCENT = 25;

    private final GuardianDao dao;
    private final WorldMutator mutator;
    private final Executor mainThreadExecutor;
    private final Executor completionExecutor;
    /**
     * Extra block radius applied to the supplemental EXPLOSION scan's spatial
     * pre-filter.
     *
     * <p>{@code volatile} because {@code /vg reload} can call
     * {@link #setExplosionSupplementalReach(int)} from off the query thread
     * (v1.3.2 Y3, P2-1 close-out). Publishing this via {@code volatile} means
     * a concurrent {@link #rollback}/{@link #restore} either observes the old
     * value or the new value — never a torn read — and avoids rebuilding the
     * engine on every knob change.
     */
    private volatile int explosionSupplementalReach;
    /**
     * Max world mutations admitted onto the server thread per tick. Default
     * {@link #BATCH_SIZE}. {@code volatile} so {@code /vg reload} can shrink
     * the slice without rebuilding the engine.
     */
    private volatile int mutationBatchSize = BATCH_SIZE;

    /**
     * Serialises plan+execute so two staff cannot plan against the same
     * pre-state and apply twice.
     *
     * <p>A fair {@link java.util.concurrent.Semaphore} with one permit rather
     * than a {@code ReentrantLock}: the asynchronous entry points release the
     * permit from whichever thread completes the result future, and a
     * {@code ReentrantLock} may only be unlocked by its owning thread.</p>
     *
     * <p>The permit is held across the whole plan-and-apply span, not per
     * batch: the hazard is two concurrent readers of the same pre-state, which
     * per-batch locking would not prevent. {@code preview} never takes it — a
     * preview mutates nothing and an operator must be able to inspect while a
     * long rollback runs.</p>
     */
    private final java.util.concurrent.Semaphore rollbackExclusion =
        new java.util.concurrent.Semaphore(1, true);

    /** Rollbacks that had to wait for the exclusion permit instead of proceeding. */
    private final java.util.concurrent.atomic.AtomicLong exclusionWaits =
        new java.util.concurrent.atomic.AtomicLong();

    /**
     * Whether this engine currently holds the permit. Informational only —
     * release uses a per-invocation token, not this flag, so a late duplicate
     * callback can never free the next holder's permit.
     */
    private final java.util.concurrent.atomic.AtomicBoolean exclusionHeld =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Current holder description, for the wait log line. */
    private volatile String exclusionHolder = "idle";

    /**
     * @param dao                action store; must not be {@code null}
     * @param mutator            loader-supplied world mutator; must not be {@code null}
     * @param mainThreadExecutor executor that runs tasks on the server tick thread;
     *                           must not be {@code null}
     */
    public RollbackEngine(GuardianDao dao, WorldMutator mutator, Executor mainThreadExecutor) {
        this(dao, mutator, mainThreadExecutor, MAX_TNT_REACH, ForkJoinPool.commonPool());
    }

    /**
     * X8 constructor variant that lets callers override the supplemental-scan
     * reach padding. Loader wiring passes
     * {@code GuardianConfig.rollback().explosionSupplementalReach()}; unit tests
     * that want to exercise a tight bound pass a smaller value here.
     *
     * @param dao                        action store; must not be {@code null}
     * @param mutator                    loader-supplied world mutator; must not be {@code null}
     * @param mainThreadExecutor         server-tick executor; must not be {@code null}
     * @param explosionSupplementalReach block-radius padding for the W5 supplemental EXPLOSION
     *                                   scan's DAO spatial predicate; must be {@code >= 0}
     * @since 1.3.1 X8
     */
    public RollbackEngine(GuardianDao dao, WorldMutator mutator, Executor mainThreadExecutor,
                          int explosionSupplementalReach) {
        this(dao, mutator, mainThreadExecutor, explosionSupplementalReach,
            ForkJoinPool.commonPool());
    }

    /**
     * Constructor variant with an explicit off-server completion executor.
     * The executor runs the DAO finalization after server-thread mutation
     * futures complete; it must not be the server-thread executor.
     */
    public RollbackEngine(GuardianDao dao, WorldMutator mutator, Executor mainThreadExecutor,
                          Executor completionExecutor) {
        this(dao, mutator, mainThreadExecutor, MAX_TNT_REACH, completionExecutor);
    }

    /** Constructor variant with explicit scan reach and completion executor. */
    public RollbackEngine(GuardianDao dao, WorldMutator mutator, Executor mainThreadExecutor,
                          int explosionSupplementalReach, Executor completionExecutor) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.mutator = Objects.requireNonNull(mutator, "mutator");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        if (explosionSupplementalReach < 0) {
            throw new IllegalArgumentException(
                "explosionSupplementalReach must be >= 0 (got " + explosionSupplementalReach + ")");
        }
        this.explosionSupplementalReach = explosionSupplementalReach;
    }

    /**
     * Hot-swap the supplemental EXPLOSION scan reach (v1.3.2 Y3, P2-1 close-out).
     *
     * <p>Called from {@code Guardian.reloadConfig} after a merged
     * {@link network.vonix.guardian.core.config.GuardianConfig} lands, so
     * operators editing {@code rollback.explosionSupplementalReach} in
     * {@code config.json} see the value applied immediately without a server
     * restart. The write is published through the {@code volatile} field so a
     * concurrently running rollback observes either the old or the new value —
     * never a torn read.
     *
     * @param reach new block-radius padding; must be {@code >= 0}
     * @throws IllegalArgumentException if {@code reach < 0}
     * @since 1.3.2 Y3
     */
    public void setExplosionSupplementalReach(int reach) {
        if (reach < 0) {
            throw new IllegalArgumentException(
                "explosionSupplementalReach must be >= 0 (got " + reach + ")");
        }
        this.explosionSupplementalReach = reach;
    }

    /**
     * Current supplemental EXPLOSION scan reach — exposed for tests and
     * {@code /vg status}.
     *
     * @return current block-radius padding
     * @since 1.3.2 Y3
     */
    public int getExplosionSupplementalReach() {
        return explosionSupplementalReach;
    }

    /**
     * Hot-swap the per-tick world-mutation slice. Values below 1 are rejected.
     * Called from {@code Guardian.reloadConfig}.
     */
    public void setMutationBatchSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("mutationBatchSize must be >= 1 (got " + size + ")");
        }
        this.mutationBatchSize = size;
    }

    /** Current per-tick world-mutation slice. */
    public int getMutationBatchSize() {
        return mutationBatchSize;
    }

    public RollbackResult rollback(QueryFilter filter, boolean preview) throws Exception {
        return rollback(filter, preview, null, RollbackOptions.defaults());
    }

    public RollbackResult restore(QueryFilter filter, boolean preview) throws Exception {
        return restore(filter, preview, null, RollbackOptions.defaults());
    }

    public RollbackResult rollback(QueryFilter filter, boolean preview, UUID actorUuid) throws Exception {
        return rollback(filter, preview, actorUuid, RollbackOptions.defaults());
    }

    public RollbackResult restore(QueryFilter filter, boolean preview, UUID actorUuid) throws Exception {
        return restore(filter, preview, actorUuid, RollbackOptions.defaults());
    }

    /**
     * Non-blocking rollback entry point. Planning and batch creation retain the
     * DAO caller-thread contract; the returned stage completes only after all
     * server-thread mutations and DAO finalization have completed.
     */
    public CompletionStage<RollbackResult> rollbackAsync(QueryFilter filter, boolean preview) throws Exception {
        return rollbackAsync(filter, preview, null, RollbackOptions.defaults());
    }

    /** Non-blocking restore entry point. */
    public CompletionStage<RollbackResult> restoreAsync(QueryFilter filter, boolean preview) throws Exception {
        return restoreAsync(filter, preview, null, RollbackOptions.defaults());
    }

    /** Non-blocking rollback entry point with actor and safety controls. */
    public CompletionStage<RollbackResult> rollbackAsync(QueryFilter filter, boolean preview,
                                                          UUID actorUuid,
                                                          RollbackOptions options) throws Exception {
        return planAndExecuteAsync(filter, RollbackResult.Mode.ROLLBACK, actorUuid, options, preview);
    }

    /** Non-blocking restore entry point with actor and safety controls. */
    public CompletionStage<RollbackResult> restoreAsync(QueryFilter filter, boolean preview,
                                                         UUID actorUuid,
                                                         RollbackOptions options) throws Exception {
        return planAndExecuteAsync(filter, RollbackResult.Mode.RESTORE, actorUuid, options, preview);
    }

    /** Execute rollback with explicit large-job safety controls. */
    public RollbackResult rollback(QueryFilter filter, boolean preview, RollbackOptions options) throws Exception {
        return rollback(filter, preview, null, options);
    }

    /** Execute restore with explicit large-job safety controls. */
    public RollbackResult restore(QueryFilter filter, boolean preview, RollbackOptions options) throws Exception {
        return restore(filter, preview, null, options);
    }

    /** Execute rollback with explicit actor + large-job safety controls. */
    public RollbackResult rollback(QueryFilter filter, boolean preview, UUID actorUuid, RollbackOptions options) throws Exception {
        return awaitPlanAndExecute(filter, RollbackResult.Mode.ROLLBACK, actorUuid, options, preview);
    }

    /** Execute restore with explicit actor + large-job safety controls. */
    public RollbackResult restore(QueryFilter filter, boolean preview, UUID actorUuid, RollbackOptions options) throws Exception {
        return awaitPlanAndExecute(filter, RollbackResult.Mode.RESTORE, actorUuid, options, preview);
    }

    /**
     * Blocking plan+execute under the exclusion permit.
     *
     * <p>Kept as the single blocking implementation so the per-actor command
     * paths, {@code /vg undo}, and tests all share one serialisation rule.</p>
     */
    private RollbackResult awaitPlanAndExecute(QueryFilter filter,
                                               RollbackResult.Mode mode,
                                               UUID actorUuid,
                                               RollbackOptions options,
                                               boolean preview) throws Exception {
        if (preview) {
            // Previews never mutate and never block a running rollback.
            return executeInternal(plan(filter, mode, actorUuid, options), true);
        }
        acquireExclusion(mode, actorUuid);
        Runnable release = newReleaser();
        try {
            // Plan AFTER winning the permit, so this request sees the world
            // state left by the rollback ahead of it, not the pre-state it read
            // while queued.
            return executeInternal(plan(filter, mode, actorUuid, options), false);
        } finally {
            release.run();
        }
    }

    /**
     * Non-blocking plan+execute under the exclusion permit. The permit is
     * released by the completion callback, whichever thread runs it.
     *
     * <p>Planning happens on the caller's thread (the DAO contract requires it),
     * so a caller that is itself waiting on the permit blocks that thread. That
     * is the intended semantics: a second staff member's rollback waits instead
     * of reading the same pre-state.</p>
     */
    private CompletionStage<RollbackResult> planAndExecuteAsync(QueryFilter filter,
                                                                RollbackResult.Mode mode,
                                                                UUID actorUuid,
                                                                RollbackOptions options,
                                                                boolean preview) throws Exception {
        if (preview) {
            return executeAsyncInternal(plan(filter, mode, actorUuid, options), true);
        }
        acquireExclusion(mode, actorUuid);
        Runnable release = newReleaser();
        CompletionStage<RollbackResult> stage;
        try {
            stage = executeAsyncInternal(plan(filter, mode, actorUuid, options), false);
        } catch (Throwable planningFailure) {
            release.run();
            throw planningFailure;
        }
        return stage.whenComplete((outcome, failure) -> release.run());
    }

    /**
     * Take the plan+execute permit, waiting if another rollback holds it.
     *
     * <p>Waiting is the correct behaviour, not a failure: two staff planning
     * against the same pre-state is the defect, and a queued rollback still
     * re-plans after it wins the permit, so it sees the post-state of the one
     * ahead of it.</p>
     */
    private void acquireExclusion(RollbackResult.Mode mode, UUID actorUuid) throws InterruptedException {
        if (rollbackExclusion.tryAcquire()) {
            exclusionHeld.set(true);
            exclusionHolder = describe(mode, actorUuid);
            return;
        }
        long waits = exclusionWaits.incrementAndGet();
        String holder = exclusionHolder;
        LOG.warn("RollbackEngine.{}: waiting for the active rollback to finish (holder={}, wait #{})",
            mode, holder, waits);
        try {
            rollbackExclusion.acquire();
        } catch (InterruptedException interrupted) {
            exclusionWaits.decrementAndGet();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        exclusionHeld.set(true);
        exclusionHolder = describe(mode, actorUuid);
        LOG.info("RollbackEngine.{}: acquired the rollback exclusion permit after waiting", mode);
    }

    /**
     * One-shot releaser for a single permit acquisition.
     *
     * <p>Returning a fresh releaser per acquisition — rather than a shared
     * "is it held?" flag — is what makes a duplicated completion callback
     * harmless: a second call on the same releaser does nothing, and it can
     * never release a permit that belongs to the next rollback in the queue.</p>
     */
    private Runnable newReleaser() {
        java.util.concurrent.atomic.AtomicBoolean released =
            new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            exclusionHeld.set(false);
            exclusionHolder = "idle";
            rollbackExclusion.release();
        };
    }

    private static String describe(RollbackResult.Mode mode, UUID actorUuid) {
        return mode + (actorUuid == null ? " by console" : " by " + actorUuid);
    }

    /**
     * How many rollbacks have had to wait for the exclusion permit — exposed for
     * {@code /vg status} and tests. Zero on a quiet server.
     */
    public long exclusionWaitCount() {
        return exclusionWaits.get();
    }

    /** Whether a rollback currently holds the plan+execute permit. */
    public boolean rollbackInProgress() {
        return exclusionHeld.get();
    }

    /**
     * Phase 1 of the W2-01 two-phase pipeline: query the DAO and build an
     * immutable {@link RollbackPlan}. No batch record is opened, no executor
     * task is submitted — the caller can inspect the plan and discard it
     * without side effects.
     */
    public RollbackPlan plan(QueryFilter filter,
                             RollbackResult.Mode mode,
                             UUID actorUuid) throws Exception {
        return plan(filter, mode, actorUuid, RollbackOptions.defaults());
    }

    /**
     * Bounded streaming variant of {@link #plan(QueryFilter, RollbackResult.Mode, UUID)}.
     * It pages DAO results and never materializes the full raw match set.
     */
    public RollbackPlan plan(QueryFilter filter,
                             RollbackResult.Mode mode,
                             UUID actorUuid,
                             RollbackOptions options) throws Exception {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(mode, "mode");
        options = RollbackOptions.normalize(options);
        requireTemporalBound(filter, mode);
        QueryFilter effective = withRolledBack(filter, mode == RollbackResult.Mode.RESTORE);
        RollbackPlan plan = streamPlan(effective, mode, actorUuid, options);
        if (plan.isEmpty()) {
            LOG.debug("RollbackEngine.{}: 0 matches", mode);
        }
        return plan;
    }

    /**
     * Phase 2 of the W2-01 two-phase pipeline: execute a previously built
     * {@link RollbackPlan}. Opens the {@code vg_rollback_batches} audit row,
     * dispatches mutations to the main-thread executor, waits for their
     * completion, marks confirmed IDs in the DAO, then closes the batch row.
     * This compatibility wrapper blocks its caller until completion; callers
     * on the server thread must use {@link #executeAsync}.
     * In {@code preview} mode no batch is opened and no mutations are dispatched.
     */
    public RollbackResult execute(RollbackPlan plan, boolean preview) throws Exception {
        Objects.requireNonNull(plan, "plan");
        if (preview) {
            return executeInternal(plan, true);
        }
        acquireExclusion(plan.mode(), plan.actorUuid());
        Runnable release = newReleaser();
        try {
            return executeInternal(plan, false);
        } finally {
            release.run();
        }
    }

    /**
     * Execute a plan without blocking for server-thread work. A non-preview
     * stage is completed only after confirmed mutation outcomes have been
     * persisted and the rollback batch has been closed.
     *
     * <p>A non-preview execute takes the same plan+execute exclusion permit as
     * the one-shot entry points, so a caller that planned earlier cannot apply
     * against a pre-state another rollback has already changed. The permit is
     * released when the returned stage completes.</p>
     */
    public CompletionStage<RollbackResult> executeAsync(RollbackPlan plan, boolean preview) throws Exception {
        Objects.requireNonNull(plan, "plan");
        if (preview) {
            return executeAsyncInternal(plan, true);
        }
        acquireExclusion(plan.mode(), plan.actorUuid());
        Runnable release = newReleaser();
        CompletionStage<RollbackResult> stage;
        try {
            stage = executeAsyncInternal(plan, false);
        } catch (Throwable failure) {
            release.run();
            throw failure;
        }
        return stage.whenComplete((outcome, failure) -> release.run());
    }

    /**
     * Execute an already-built plan. Does not take the exclusion permit — every
     * public entry point that mutates the world holds it for this call.
     */
    private CompletionStage<RollbackResult> executeAsyncInternal(RollbackPlan plan, boolean preview) throws Exception {
        RollbackResult.Mode mode = plan.mode();
        if (mode == null) {
            throw new IllegalArgumentException(
                "RollbackPlan has no mode — use RollbackEngine.plan(...) to build it");
        }
        UUID actorUuid = plan.actorUuid();
        QueryFilter effective = plan.originalFilter();
        List<Long> affectedIds = plan.actionIds();
        List<Long> skippedIds = plan.skippedIds();
        Map<RollbackResult.SkipReason, Integer> skippedByReason = plan.skippedByReason();
        int planned = plan.plannedSteps();

        if (!plan.skipped().isEmpty()) {
            LOG.warn("RollbackEngine.{}: {} action(s) excluded at plan time ({})",
                mode, plan.skipped().size(), describeSkips(plan));
        }

        if (preview || (planned == 0 && skippedIds.isEmpty())) {
            // A genuinely empty match opens nothing. A request that matched rows
            // but could not apply any of them still opens a batch below: the
            // operator asked for something, and the audit row is where that
            // request plus its named reasons is recorded.
            //
            // A preview also carries where it would touch the world, so a client
            // can show the operator the area before anything changes. This is the
            // only place the plan is still in hand; after this the rows are gone.
            PreviewOutline outline = preview
                ? PreviewOutline.fromPlanned(plan.ordered())
                : PreviewOutline.empty();
            return CompletableFuture.completedFuture(new RollbackResult(actorUuid, mode, preview,
                affectedIds, skippedIds, planned, 0, effective,
                RollbackResult.Status.SUCCESS, 0L, 0, 0, 0, true, skippedByReason, outline));
        }

        String filterJson = encodeFilter(effective);
        long batchId = dao.openRollbackBatch(actorUuid, mode.ordinal(), filterJson, affectedIds);

        if (planned == 0) {
            // Every row was resolved at plan time. Nothing is dispatched, so the
            // batch is closed immediately rather than left open for recovery.
            return CompletableFuture.completedFuture(closeEmptyBatch(
                batchId, actorUuid, mode, effective, skippedIds, skippedByReason, planned));
        }

        CompletionStage<List<WorldMutationResult>> mutations;
        try {
            mutations = dispatchBatches(plan.ordered(), mode);
        } catch (RuntimeException e) {
            LOG.error("RollbackEngine.{}: batch id={} failed during dispatch; vg_rollback_batches row left OPEN for recovery",
                mode, batchId, e);
            return CompletableFuture.completedFuture(failedResult(
                batchId, actorUuid, mode, effective, skippedIds, skippedByReason, planned, 0, e));
        }
        return finalizeAsync(mutations, batchId, actorUuid, mode, effective, skippedIds,
            skippedByReason, planned);
    }

    /** Engine-side per-reason log rendering; the operator text lives on the result. */
    private static String describeSkips(RollbackPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<RollbackResult.SkipReason, Integer> e : plan.skippedByReason().entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.length() == 0 ? "reason unavailable" : sb.toString();
    }

    /**
     * Close a batch that was opened for a request whose every row was resolved
     * at plan time. Nothing was dispatched, so no world state is pending; the
     * batch is finished so startup recovery does not later report it as a crash.
     */
    private RollbackResult closeEmptyBatch(long batchId,
                                            UUID actorUuid,
                                            RollbackResult.Mode mode,
                                            QueryFilter effective,
                                            List<Long> skippedIds,
                                            Map<RollbackResult.SkipReason, Integer> skippedByReason,
                                            int planned) {
        boolean closed = false;
        try {
            int updated = dao.closeRollbackBatch(batchId);
            closed = updated == 1;
            if (!closed) {
                LOG.error("RollbackEngine.{}: closeRollbackBatch id={} updated {} rows (expected 1) "
                        + "for a fully-skipped request", mode, batchId, updated);
            }
        } catch (Exception e) {
            LOG.error("RollbackEngine.{}: failed to close fully-skipped batch id={}", mode, batchId, e);
        }
        return new RollbackResult(actorUuid, mode, false,
            List.of(), skippedIds, planned, 0, effective,
            closed ? RollbackResult.Status.SUCCESS : RollbackResult.Status.PARTIAL,
            batchId, 0, 0, 0, closed, skippedByReason);
    }

    private RollbackResult executeInternal(RollbackPlan plan, boolean preview) throws Exception {
        try {
            return executeAsyncInternal(plan, preview).toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private CompletionStage<RollbackResult> finalizeAsync(CompletionStage<List<WorldMutationResult>> mutations,
                                                            long batchId,
                                                            UUID actorUuid,
                                                            RollbackResult.Mode mode,
                                                            QueryFilter effective,
                                                            List<Long> plannedSkippedIds,
                                                            Map<RollbackResult.SkipReason, Integer> plannedSkipsByReason,
                                                            int planned) {
        CompletableFuture<RollbackResult> result = new CompletableFuture<>();
        mutations.whenComplete((outcomes, failure) -> executeCompletion(() -> {
            try {
                if (failure != null) {
                    Throwable cause = unwrapCompletionFailure(failure);
                    LOG.error("RollbackEngine.{}: batch id={} failed mid-flight; vg_rollback_batches row left OPEN for recovery",
                        mode, batchId, cause);
                    result.complete(failedResult(batchId, actorUuid, mode, effective,
                        plannedSkippedIds, plannedSkipsByReason, planned, 0, cause));
                    return;
                }
                result.complete(finishBatch(batchId, actorUuid, mode, effective,
                    plannedSkippedIds, plannedSkipsByReason, planned, outcomes));
            } catch (Throwable finalizationFailure) {
                result.completeExceptionally(finalizationFailure);
            }
        }, "batch finalization"));
        return result;
    }

    /**
     * Terminal result for a dispatch that died before producing outcomes.
     *
     * @param partialApplied number of rows already applied when the failure hit;
     *                       their world state is changed and not rolled back
     */
    private RollbackResult failedResult(long batchId,
                                        UUID actorUuid,
                                        RollbackResult.Mode mode,
                                        QueryFilter effective,
                                        List<Long> plannedSkippedIds,
                                        Map<RollbackResult.SkipReason, Integer> skippedByReason,
                                        int planned,
                                        int partialApplied,
                                        Throwable cause) {
        return new RollbackResult(actorUuid, mode, false,
            List.of(), plannedSkippedIds, planned, partialApplied, effective,
            RollbackResult.Status.FAILED, batchId, 1, 0, 0, false, skippedByReason);
    }

    private RollbackResult finishBatch(long batchId,
                                       UUID actorUuid,
                                       RollbackResult.Mode mode,
                                       QueryFilter effective,
                                       List<Long> plannedSkippedIds,
                                       Map<RollbackResult.SkipReason, Integer> plannedSkipsByReason,
                                       int planned,
                                       List<WorldMutationResult> outcomes) {
        List<Long> appliedIds = new ArrayList<>();
        List<Long> dispatchSkipped = new ArrayList<>();
        Map<RollbackResult.SkipReason, Integer> dispatchSkipsByReason =
            new java.util.EnumMap<>(RollbackResult.SkipReason.class);
        List<WorldMutationResult> failed = new ArrayList<>();
        List<WorldMutationResult> compensated = new ArrayList<>();
        List<WorldMutationResult> repairRequired = new ArrayList<>();
        for (WorldMutationResult outcome : outcomes) {
            switch (outcome.status()) {
                case APPLIED -> appliedIds.add(outcome.actionId());
                case SKIPPED -> {
                    dispatchSkipped.add(outcome.actionId());
                    // A dispatch skip must still name its reason, or the count
                    // regresses to the unexplained number this lane removes.
                    RollbackResult.SkipReason reason = outcome.skipReason() == null
                        ? RollbackResult.SkipReason.UNTRACKED_STATE
                        : outcome.skipReason();
                    dispatchSkipsByReason.merge(reason, 1, Integer::sum);
                }
                case COMPENSATED -> compensated.add(outcome);
                case REPAIR_REQUIRED -> repairRequired.add(outcome);
                case FAILED -> failed.add(outcome);
            }
        }

        List<Long> allSkipped = new ArrayList<>(plannedSkippedIds.size() + dispatchSkipped.size());
        allSkipped.addAll(plannedSkippedIds);
        allSkipped.addAll(dispatchSkipped);
        Map<RollbackResult.SkipReason, Integer> allSkipsByReason =
            mergeReasons(plannedSkipsByReason, dispatchSkipsByReason);

        boolean targetFlag = mode == RollbackResult.Mode.ROLLBACK;
        boolean persistOk = true;
        if (!appliedIds.isEmpty()) {
            try {
                markApplied(batchId, appliedIds, targetFlag);
            } catch (RuntimeException e) {
                persistOk = false;
                LOG.error("RollbackEngine.{}: failed to mark applied prefix for batch id={}", mode, batchId, e);
            }
        }
        if (!repairRequired.isEmpty()) {
            try {
                persistRepairRequired(batchId, repairRequired);
            } catch (RuntimeException e) {
                persistOk = false;
                LOG.error("RollbackEngine.{}: failed to persist repair-required for batch id={}", mode, batchId, e);
            }
        }

        RollbackResult.Status status;
        if (!repairRequired.isEmpty()) {
            status = RollbackResult.Status.REPAIR_REQUIRED;
        } else if (!compensated.isEmpty()) {
            status = RollbackResult.Status.COMPENSATED;
        } else if (!failed.isEmpty() && appliedIds.isEmpty()) {
            status = RollbackResult.Status.FAILED;
        } else if (!failed.isEmpty() || !persistOk) {
            status = RollbackResult.Status.PARTIAL;
        } else {
            status = RollbackResult.Status.SUCCESS;
        }

        boolean canClose = persistOk && failed.isEmpty() && compensated.isEmpty() && repairRequired.isEmpty();
        boolean batchClosed = false;
        if (canClose) {
            try {
                int closed = dao.closeRollbackBatch(batchId);
                if (closed != 1) {
                    LOG.error("RollbackEngine.{}: closeRollbackBatch id={} updated {} rows (expected 1); "
                            + "audit row considered unclosed", mode, batchId, closed);
                    if (status == RollbackResult.Status.SUCCESS) {
                        status = RollbackResult.Status.PARTIAL;
                    }
                } else {
                    batchClosed = true;
                }
            } catch (Exception e) {
                LOG.error("RollbackEngine.{}: failed to close batch id={}", mode, batchId, e);
                if (status == RollbackResult.Status.SUCCESS) {
                    status = RollbackResult.Status.PARTIAL;
                }
            }
        } else {
            LOG.warn("RollbackEngine.{}: batch id={} left OPEN status={} applied={} skipped={} failed={} compensated={} repairRequired={}",
                mode, batchId, status, appliedIds.size(), allSkipped.size(),
                failed.size(), compensated.size(), repairRequired.size());
        }

        return new RollbackResult(actorUuid, mode, false,
            appliedIds, allSkipped, planned, appliedIds.size(), effective,
            status, batchId, failed.size(), compensated.size(), repairRequired.size(), batchClosed,
            allSkipsByReason);
    }

    /** Add two reason histograms into one immutable map; zero buckets are dropped. */
    private static Map<RollbackResult.SkipReason, Integer> mergeReasons(
            Map<RollbackResult.SkipReason, Integer> a,
            Map<RollbackResult.SkipReason, Integer> b) {
        if ((a == null || a.isEmpty()) && (b == null || b.isEmpty())) {
            return Map.of();
        }
        Map<RollbackResult.SkipReason, Integer> merged =
            new java.util.EnumMap<>(RollbackResult.SkipReason.class);
        if (a != null) {
            for (Map.Entry<RollbackResult.SkipReason, Integer> e : a.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    merged.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }
        if (b != null) {
            for (Map.Entry<RollbackResult.SkipReason, Integer> e : b.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    merged.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }
        return merged.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(merged);
    }

    private void markApplied(long batchId, List<Long> ids, boolean targetFlag) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            dao.markRolledBack(ids, targetFlag);
        } catch (Exception e) {
            LOG.error("RollbackEngine: failed to mark confirmed mutations for batch id={}; row left OPEN for recovery",
                batchId, e);
            throw new CompletionException(e);
        }
    }

    private void persistRepairRequired(long batchId, List<WorldMutationResult> repairRequired) {
        List<GuardianDao.RepairRequired> rows = new ArrayList<>(repairRequired.size());
        long now = System.currentTimeMillis();
        for (WorldMutationResult outcome : repairRequired) {
            String reason = outcome.failure() == null
                    ? "uncompensated world mutation"
                    : outcome.failure().toString();
            rows.add(new GuardianDao.RepairRequired(outcome.actionId(), outcome.pairId(), batchId, reason, now));
        }
        try {
            int persisted = dao.markRepairRequired(rows);
            if (persisted < rows.size()) {
                LOG.error("RollbackEngine: markRepairRequired persisted {} of {} repair-required rows for batch id={}",
                        persisted, rows.size(), batchId);
            }
        } catch (Exception e) {
            LOG.error("RollbackEngine: failed to persist repair-required state for batch id={}",
                    batchId, e);
            throw new CompletionException(e);
        }
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        CompletableFuture<T> stage = new CompletableFuture<>();
        stage.completeExceptionally(failure);
        return stage;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    /**
     * Schedule off-server completion work with a process-local fallback. A
     * stopping primary completion executor must not discard outcomes that were
     * already applied on the server thread; the common pool preserves the DAO
     * caller-thread contract in that failure mode. Inline execution is the
     * final fail-safe only when the process-wide fallback also rejects.
     */
    private void executeCompletion(Runnable task, String operation) {
        try {
            completionExecutor.execute(task);
            return;
        } catch (RuntimeException primaryFailure) {
            LOG.warn("RollbackEngine: {} executor rejected work; using common-pool fallback", operation,
                primaryFailure);
        }
        try {
            ForkJoinPool.commonPool().execute(task);
            return;
        } catch (RuntimeException fallbackFailure) {
            LOG.error("RollbackEngine: common-pool fallback rejected {}; running inline", operation,
                fallbackFailure);
        }
        task.run();
    }

    /**
     * Startup recovery: scan for batches that were opened but never closed
     * (server crashed mid-rollback) and log one summary line. This method is
     * read-only. It must not replay container or inventory edits, and it must
     * not close the batch. Operators use {@code /vg batch} to inspect or mark
     * the audit row finished.
     *
     * @return the lines that were logged; empty when nothing was open
     */
    public List<String> recoverIncompleteBatches() throws Exception {
        List<Long> ids = dao.findIncompleteBatchActionIds();
        List<GuardianDao.OpenRollbackBatch> open = dao.findOpenRollbackBatches(20);
        List<String> notice = RollbackBatches.recoveryNotice(open, ids);
        for (String line : notice) {
            LOG.warn("RollbackEngine: {}", line);
        }
        return notice;
    }

    // ---------------------------------------------------------------------

    /**
     * Pages the DAO with the filter as given and incrementally builds a bounded
     * plan. The {@code rolledBack} predicate MUST already be set on
     * {@code filter}; SQL-side filtering keeps the scanned row count meaningful.
     */
    private RollbackPlan streamPlan(QueryFilter filter,
                                    RollbackResult.Mode mode,
                                    UUID actorUuid,
                                    RollbackOptions options) throws Exception {
        RollbackPlan.StreamingBuilder builder = RollbackPlan.streaming(filter, mode, actorUuid);
        int offset = 0;
        int scanned = 0;
        int pages = 0;
        Action seekAfter = null;

        while (true) {
            if (options.isCancelRequested()) {
                RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                options.publish(progress);
                throw new RollbackCancelledException(progress);
            }

            int remainingScanBudget = options.maxScannedActions() - scanned;
            if (remainingScanBudget <= 0) {
                if (hasMoreRows(filter, seekAfter, offset)) {
                    RollbackProgress progress = progress(pages, scanned, builder, true, false, false);
                    options.publish(progress);
                    throw new RollbackLimitExceededException(
                        "Rollback planning exceeded scan cap of " + options.maxScannedActions() + " action(s)",
                        progress);
                }
                break;
            }

            int limit = Math.min(options.pageSize(), remainingScanBudget);
            // v1.3.1 X6 (P3-6): request limit+1 rows so we can detect "has more" without
            // a follow-up dao.query() round-trip below. Only the first `limit` rows
            // are actually consumed; the extra row is a boolean signal.
            GuardianDao.QueryPage fetched = fetchNext(filter, seekAfter, offset, limit + 1);
            if (fetched.truncated()) {
                RollbackProgress progress = progress(pages, scanned, builder, true, false, false);
                options.publish(progress);
                throw new RollbackLimitExceededException(
                    "Rollback planning stopped because the DAO result cap truncated a page",
                    progress);
            }
            List<Action> pageWithProbe = fetched.rows();
            if (pageWithProbe.isEmpty()) {
                break;
            }
            boolean probeHasMore = pageWithProbe.size() > limit;
            List<Action> page = probeHasMore
                    ? new ArrayList<>(pageWithProbe.subList(0, limit))
                    : pageWithProbe;
            pages++;

            List<Action> orderedPage = new ArrayList<>(page);
            orderedPage.sort((a, b) -> {
                int c = Long.compare(b.timestamp(), a.timestamp());
                return c != 0 ? c : Long.compare(b.id(), a.id());
            });

            for (Action action : orderedPage) {
                if (options.isCancelRequested()) {
                    RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                    options.publish(progress);
                    throw new RollbackCancelledException(progress);
                }
                scanned++;
                builder.add(action);
                if (builder.plannedSteps() > options.maxPlannedSteps()) {
                    RollbackProgress progress = progress(pages, scanned, builder, false, true, false);
                    options.publish(progress);
                    throw new RollbackLimitExceededException(
                        "Rollback planning exceeded mutation cap of " + options.maxPlannedSteps() + " step(s)",
                        progress);
                }
            }

            boolean fullPage = page.size() == limit;
            boolean scanBudgetExhausted = scanned >= options.maxScannedActions();
            // v1.3.1 X6 (P3-6): reuse the +1 probe result instead of a separate dao.query.
            boolean scanLimitReached = scanBudgetExhausted && fullPage && probeHasMore;
            RollbackProgress progress = progress(pages, scanned, builder, scanLimitReached, false, false);
            options.publish(progress);
            if (scanLimitReached) {
                throw new RollbackLimitExceededException(
                    "Rollback planning exceeded scan cap of " + options.maxScannedActions() + " action(s)",
                    progress);
            }
            if (!fullPage) {
                break;
            }
            seekAfter = orderedPage.get(orderedPage.size() - 1);
            offset += page.size();
        }

        // W5 (v1.3.0): supplemental EXPLOSION scan — the primary DAO query filters
        // by center coord against the caller's radius, so it misses TNT whose center
        // is outside the box but whose affected-list reaches into it. Match
        // CoreProtect: loop through the affected-list at rollback time and admit
        // the row if ANY block in it falls within the caller's radius.
        supplementExplosions(filter, builder, options, pages, scanned);
        supplementPairedActions(builder, options, pages, scanned);
        return builder.build();
    }

    /**
     * After the primary (and explosion-supplement) scan, pull every other
     * action that shares a durable {@code pair_id} with a planned row. This
     * is what makes fire/break pairing survive persistence and restart: the
     * sibling may sit two blocks outside the caller's radius.
     */
    private void supplementPairedActions(RollbackPlan.StreamingBuilder builder,
                                         RollbackOptions options,
                                         int pages,
                                         int scanned) throws Exception {
        java.util.Set<Long> pairIds = builder.pairIds();
        if (pairIds == null || pairIds.isEmpty()) {
            return;
        }
        List<Action> siblings = dao.findByPairIds(pairIds);
        if (siblings == null || siblings.isEmpty()) {
            return;
        }
        for (Action action : siblings) {
            if (options.isCancelRequested()) {
                RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                options.publish(progress);
                throw new RollbackCancelledException(progress);
            }
            builder.add(action);
            if (builder.plannedSteps() > options.maxPlannedSteps()) {
                RollbackProgress progress = progress(pages, scanned, builder, false, true, false);
                options.publish(progress);
                throw new RollbackLimitExceededException(
                    "Rollback planning exceeded mutation cap of " + options.maxPlannedSteps() + " step(s)",
                    progress);
            }
        }
    }

    /**
     * W5 — after the primary paged scan, sweep EXPLOSION rows whose center is
     * OUTSIDE the caller's radius but whose affected-list reaches into it.
     *
     * <p><b>X8 (v1.3.1)</b>: the supplemental filter clones {@code base} but
     * <em>widens</em> the spatial predicate by {@link #explosionSupplementalReach}
     * blocks (default {@link #MAX_TNT_REACH}) rather than dropping it. This keeps
     * the DAO scan bounded on griefing-storm servers — instead of "every
     * EXPLOSION row in this world in the time window", the DAO reads only rows
     * whose blast-center could plausibly reach into the caller's radius. Row
     * admission still uses {@link ExplosionAffectedList#anyWithinRadius}, so
     * widening the pre-filter is a strict superset of the correct answer:
     * blasts whose center is far outside the padded box cannot have an
     * affected-list that reaches into the original radius (vanilla TNT's
     * affected-list stays within its blast radius; modded mega-explosives that
     * exceed 16 blocks can raise {@code rollback.explosionSupplementalReach}).</p>
     *
     * <p>Rows already picked up by the primary scan (center inside the
     * un-widened box) are re-checked with a cheap "center inside box?" test and
     * skipped instead of double-added.</p>
     *
     * <p>Skipped when the filter has no spatial constraint ({@code radius==null}
     * or {@code #global}), when no center is set, or when the filter's action
     * list excludes EXPLOSION.</p>
     */
    private void supplementExplosions(QueryFilter base,
                                      RollbackPlan.StreamingBuilder builder,
                                      RollbackOptions options,
                                      int pages,
                                      int scanned) throws Exception {
        Integer r = base.radius();
        if (r == null || r < 0) return;                       // no spatial predicate to widen
        if (base.centerX() == null || base.centerZ() == null) return;
        if (!filterAdmitsExplosion(base)) return;

        QueryFilter supp = withExplosionOnlyWidenedSpatial(base, explosionSupplementalReach);
        int centerX = base.centerX();
        Integer centerY = base.centerY();
        int centerZ = base.centerZ();
        // Un-widened box (the caller's original radius) is what we use to detect
        // rows the primary scan already covered.
        int minX = centerX - r, maxX = centerX + r;
        int minZ = centerZ - r, maxZ = centerZ + r;
        Integer minY = centerY == null ? null : centerY - r;
        Integer maxY = centerY == null ? null : centerY + r;

        int offset = 0;
        Action seekAfter = null;
        while (true) {
            if (options.isCancelRequested()) {
                RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                options.publish(progress);
                throw new RollbackCancelledException(progress);
            }
            int remainingScanBudget = options.maxScannedActions() - scanned;
            if (remainingScanBudget <= 0) {
                if (hasMoreRows(supp, seekAfter, offset)) {
                    RollbackProgress progress = progress(pages, scanned, builder, true, false, false);
                    options.publish(progress);
                    throw new RollbackLimitExceededException(
                        "Rollback planning exceeded scan cap of " + options.maxScannedActions() + " action(s)",
                        progress);
                }
                break;
            }
            int limit = Math.min(options.pageSize(), remainingScanBudget);
            // Same +1 probe as the primary scan: a full page without an extra
            // row is EOF, while a capped page is reported via truncated=true.
            GuardianDao.QueryPage fetched = fetchNext(supp, seekAfter, offset, limit + 1);
            if (fetched.truncated()) {
                RollbackProgress progress = progress(pages, scanned, builder, true, false, false);
                options.publish(progress);
                throw new RollbackLimitExceededException(
                    "Rollback planning stopped because the DAO result cap truncated a page",
                    progress);
            }
            List<Action> pageWithProbe = fetched.rows();
            if (pageWithProbe.isEmpty()) break;
            boolean probeHasMore = pageWithProbe.size() > limit;
            List<Action> page = probeHasMore
                    ? new ArrayList<>(pageWithProbe.subList(0, limit))
                    : pageWithProbe;
            pages++;

            List<Action> orderedPage = new ArrayList<>(page);
            orderedPage.sort((a, b) -> {
                int c = Long.compare(b.timestamp(), a.timestamp());
                return c != 0 ? c : Long.compare(b.id(), a.id());
            });

            for (Action action : orderedPage) {
                if (options.isCancelRequested()) {
                    RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                    options.publish(progress);
                    throw new RollbackCancelledException(progress);
                }
                scanned++;
                // Skip if center is already inside the box — the primary scan
                // already added this row.
                boolean centerInside = action.x() >= minX && action.x() <= maxX
                    && action.z() >= minZ && action.z() <= maxZ
                    && (minY == null || (action.y() >= minY && action.y() <= maxY));
                if (centerInside) continue;

                ExplosionAffectedList list = ExplosionAffectedList.parse(action.targetId());
                if (list.isEmpty()) continue;
                if (!list.anyWithinRadius(centerX, centerY, centerZ, r)) continue;

                builder.add(action);
                if (builder.plannedSteps() > options.maxPlannedSteps()) {
                    RollbackProgress progress = progress(pages, scanned, builder, false, true, false);
                    options.publish(progress);
                    throw new RollbackLimitExceededException(
                        "Rollback planning exceeded mutation cap of " + options.maxPlannedSteps() + " step(s)",
                        progress);
                }
            }

            boolean fullPage = page.size() == limit;
            RollbackProgress progress = progress(pages, scanned, builder, false, false, false);
            options.publish(progress);
            if (!fullPage) break;
            seekAfter = orderedPage.get(orderedPage.size() - 1);
            offset += page.size();
        }
    }

    /**
     * Whether the caller's action filter admits EXPLOSION rows. Returns
     * {@code true} when {@code actions} is empty (= all types) OR contains an
     * explicit {@code EXPLOSION} entry.
     */
    private static boolean filterAdmitsExplosion(QueryFilter f) {
        if (f.actions() == null || f.actions().isEmpty()) return true;
        for (QueryFilter.ActionSelect a : f.actions()) {
            if (a.type() == ActionType.EXPLOSION) return true;
        }
        return false;
    }

    /**
     * X8 (v1.3.1): copy of {@code base} with (a) the spatial predicate
     * <em>widened</em> outward by {@code reach} blocks on x/y/z (the y widening
     * is skipped when {@code centerY} is unset, matching the primary scan's
     * behavior) and (b) the action list forced to EXPLOSION only. Used by the
     * W5 supplemental scan so the DAO stays bounded while still catching
     * blasts whose center sits outside the caller's radius but whose
     * affected-list reaches into it.
     *
     * <p>The final row admission is still done in-Java via
     * {@link ExplosionAffectedList#anyWithinRadius}; this method only relaxes
     * the DAO pre-filter, it never over-admits rows.</p>
     */
    private static QueryFilter withExplosionOnlyWidenedSpatial(QueryFilter base, int reach) {
        Integer r = base.radius();
        Integer widenedRadius = (r == null || r < 0) ? r : r + reach;
        return new QueryFilter(
            base.users(),
            base.sinceMillis(),
            base.untilMillis(),
            widenedRadius,        // widened by reach — was: null
            base.worldSel(),
            base.centerX(),       // preserved — was: null
            base.centerY(),       // preserved — was: null
            base.centerZ(),       // preserved — was: null
            List.of(new QueryFilter.ActionSelect(ActionType.EXPLOSION, QueryFilter.ActionSelect.Sign.ANY)),
            base.include(),
            base.exclude(),
            base.rolledBack(),
            base.countOnly(),
            base.preview(),
            base.verbose(),
            base.silent(),
            base.optimize(),
            null,
            base.actionIds()                  // worldEditPlayer cleared — WE region already covers primary
        );
    }

    private boolean hasMoreRows(QueryFilter filter, Action seekAfter, int offset) throws Exception {
        GuardianDao.QueryPage probe = fetchNext(filter, seekAfter, offset, 1);
        // A truncated one-row probe still proves residual work exists behind the
        // DAO cap; empty means EOF only when the page is not truncated.
        return probe.truncated() || !probe.rows().isEmpty();
    }

    /**
     * Sequential page fetch. After the first page, prefer a keyset seek so
     * later pages do not pay {@code OFFSET} skip of already-consumed rows.
     * {@code queryPageAfter} returning {@code null} (mocks / older DAOs)
     * falls back to {@link #fetchPage}.
     */
    private GuardianDao.QueryPage fetchNext(QueryFilter filter, Action seekAfter, int offset, int limit)
            throws Exception {
        if (seekAfter != null) {
            GuardianDao.QueryPage seek = dao.queryPageAfter(
                    filter, seekAfter.timestamp(), seekAfter.id(), limit);
            if (seek != null) {
                return seek;
            }
        }
        return fetchPage(filter, offset, limit);
    }

    private GuardianDao.QueryPage fetchPage(QueryFilter filter, int offset, int limit) throws Exception {
        GuardianDao.QueryPage fetched = dao.queryPage(filter, offset, limit);
        // Preserve compatibility with mocks and older third-party DAO
        // implementations that do not execute the additive default method.
        return fetched != null
            ? fetched
            : new GuardianDao.QueryPage(dao.query(filter, offset, limit), false);
    }

    private static RollbackProgress progress(int pages,
                                             int scanned,
                                             RollbackPlan.StreamingBuilder builder,
                                             boolean scanLimitReached,
                                             boolean plannedLimitReached,
                                             boolean cancelled) {
        return new RollbackProgress(pages, scanned, builder.plannedSteps(), builder.skippedActions(),
            scanLimitReached, plannedLimitReached, cancelled);
    }

    private static void requireTemporalBound(QueryFilter filter, RollbackResult.Mode mode) {
        if (filter.sinceMillis() == null && filter.untilMillis() == null) {
            throw new IllegalArgumentException(mode + " requires an explicit time filter (t:<age>)");
        }
    }

    /**
     * Returns a copy of {@code base} with {@code rolledBack} forced to the
     * requested value. Other fields are preserved verbatim. We do NOT use the
     * builder because the builder collapses null lists to empty — which is
     * fine, but a record-component copy is clearer here.
     */
    private static QueryFilter withRolledBack(QueryFilter base, boolean rolledBack) {
        return new QueryFilter(
            base.users(),
            base.sinceMillis(),
            base.untilMillis(),
            base.radius(),
            base.worldSel(),
            base.centerX(), base.centerY(), base.centerZ(),
            base.actions(),
            base.include(),
            base.exclude(),
            rolledBack,
            base.countOnly(),
            base.preview(),
            base.verbose(),
            base.silent(),
            base.optimize(),
            base.worldEditPlayer(),
            base.actionIds()
        );
    }

    /**
     * Compact JSON snapshot of a filter, persisted on the batch record for
     * operator forensics. Built as a plain {@link LinkedHashMap} so we don't
     * depend on Gson reflectively grokking every record component.
     */
    static String encodeFilter(QueryFilter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", f.users());
        m.put("sinceMillis", f.sinceMillis());
        m.put("untilMillis", f.untilMillis());
        m.put("radius", f.radius());
        m.put("worldSel", f.worldSel());
        m.put("centerX", f.centerX());
        m.put("centerY", f.centerY());
        m.put("centerZ", f.centerZ());
        m.put("actions", f.actions());
        m.put("include", f.include());
        m.put("exclude", f.exclude());
        m.put("rolledBack", f.rolledBack());
        m.put("countOnly", f.countOnly());
        m.put("preview", f.preview());
        m.put("verbose", f.verbose());
        m.put("silent", f.silent());
        return GSON.toJson(m);
    }

    private CompletionStage<List<WorldMutationResult>> dispatchBatches(List<Action> ordered,
                                                                        RollbackResult.Mode mode) {
        CompletableFuture<List<WorldMutationResult>> result = new CompletableFuture<>();
        dispatchNextBatch(ordered, mode, 0, new ArrayList<>(), result, new HashSet<>());
        return result;
    }

    /**
     * Handoff exactly one mutation batch to the server executor. The next batch
     * is admitted only from the off-server completion executor after the prior
     * batch has returned. This keeps world access on the server thread while
     * preventing large rollbacks from preloading an unbounded number of tasks
     * into a modded server's tick executor.
     */
    private void dispatchNextBatch(List<Action> ordered,
                                   RollbackResult.Mode mode,
                                   int start,
                                   List<WorldMutationResult> outcomes,
                                   CompletableFuture<List<WorldMutationResult>> result,
                                   Set<Long> consumedIds) {
        if (start >= ordered.size()) {
            result.complete(outcomes);
            return;
        }
        List<Action> batch = new ArrayList<>();
        int cursor = start;
        int slice = mutationBatchSize;
        while (cursor < ordered.size() && batch.size() < slice) {
            Action a = ordered.get(cursor++);
            if (a.id() >= 0L && !consumedIds.add(a.id())) {
                continue;
            }
            batch.add(a);
            if (network.vonix.guardian.core.event.InventoryReplacementPairs.isMember(a)) {
                Action sibling = network.vonix.guardian.core.event.InventoryReplacementPairs.siblingOf(a, ordered);
                if (sibling != null && (sibling.id() < 0L || consumedIds.add(sibling.id()))
                        && !batch.contains(sibling)) {
                    batch.add(sibling);
                }
            }
        }
        if (batch.isEmpty()) {
            result.complete(outcomes);
            return;
        }
        List<Action> immutableBatch = List.copyOf(batch);
        final int nextStart = cursor;
        CompletableFuture<List<WorldMutationResult>> completion = new CompletableFuture<>();
        try {
            mainThreadExecutor.execute(() -> {
                try {
                    completion.complete(applyBatch(immutableBatch, mode));
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            appendFailed(outcomes, immutableBatch, 0, failure);
            appendUnconsumedFailed(outcomes, ordered, nextStart, consumedIds, failure);
            result.complete(outcomes);
            return;
        }

        completion.whenComplete((batchOutcomes, failure) -> {
            Runnable continuation = () -> {
                if (failure != null) {
                    Throwable cause = unwrapCompletionFailure(failure);
                    appendFailed(outcomes, immutableBatch, 0, cause);
                    appendUnconsumedFailed(outcomes, ordered, nextStart, consumedIds, cause);
                    result.complete(outcomes);
                    return;
                }
                outcomes.addAll(batchOutcomes);
                dispatchNextBatch(ordered, mode, nextStart, outcomes, result, consumedIds);
            };
            executeCompletion(continuation, "batch continuation");
        });
    }

    private static void appendUnconsumedFailed(List<WorldMutationResult> outcomes,
                                               List<Action> ordered,
                                               int start,
                                               Set<Long> consumedIds,
                                               Throwable failure) {
        for (int i = start; i < ordered.size(); i++) {
            Action leftover = ordered.get(i);
            if (leftover.id() >= 0L && !consumedIds.add(leftover.id())) {
                continue;
            }
            outcomes.add(WorldMutationResult.failed(leftover.id(), failure));
        }
    }

    /**
     * Mark every action from {@code start} onward as not applied.
     *
     * <p>When {@code anythingAlreadyApplied} is true the world is already half
     * changed, so an unapplied row is {@code REPAIR_REQUIRED}, not a plain
     * {@code FAILED}: the operator has to be able to find and finish the gap.
     * Reporting these as ordinary failures is what let a partially applied
     * explosion disappear from `vg_repair_required` entirely.</p>
     */
    private static void appendFailed(List<WorldMutationResult> outcomes,
                                     List<Action> ordered,
                                     int start,
                                     Throwable failure) {
        appendFailed(outcomes, ordered, start, failure, false);
    }

    private static void appendFailed(List<WorldMutationResult> outcomes,
                                     List<Action> ordered,
                                     int start,
                                     Throwable failure,
                                     boolean anythingAlreadyApplied) {
        for (int i = start; i < ordered.size(); i++) {
            long id = ordered.get(i).id();
            outcomes.add(anythingAlreadyApplied
                ? WorldMutationResult.repairRequired(id, ordered.get(i).pairId(),
                    new IllegalStateException("not applied after an earlier row changed the world", failure))
                : WorldMutationResult.failed(id, failure));
        }
    }

    private List<WorldMutationResult> applyBatch(List<Action> batch, RollbackResult.Mode mode) {
        List<WorldMutationResult> outcomes = new ArrayList<>(batch.size());
        Set<Integer> done = new HashSet<>();
        for (int i = 0; i < batch.size(); i++) {
            if (!done.add(i)) {
                continue;
            }
            Action a = batch.get(i);
            Action sibling = network.vonix.guardian.core.event.InventoryReplacementPairs.siblingOf(a, batch);
            if (sibling != null) {
                int siblingIndex = batch.indexOf(sibling);
                if (siblingIndex >= 0) {
                    done.add(siblingIndex);
                }
                outcomes.addAll(applyInventoryPair(a, sibling, mode));
            } else if (network.vonix.guardian.core.event.InventoryReplacementPairs.isMember(a)) {
                IllegalStateException failure = new IllegalStateException(
                    "Incomplete inventory replacement pair for action id=" + a.id()
                        + " pairId=" + a.pairId());
                LOG.warn("RollbackEngine: refusing lone inventory pair member id={} pairId={}",
                    a.id(), a.pairId());
                outcomes.add(WorldMutationResult.failed(a.id(), failure));
            } else {
                Action hopperSibling = network.vonix.guardian.core.event.HopperTransportPairs.siblingOf(a, batch);
                if (hopperSibling != null) {
                    int siblingIndex = batch.indexOf(hopperSibling);
                    if (siblingIndex >= 0) {
                        done.add(siblingIndex);
                    }
                    outcomes.addAll(applyHopperPair(a, hopperSibling, mode));
                } else if (network.vonix.guardian.core.event.HopperTransportPairs.isMember(a)) {
                    IllegalStateException failure = new IllegalStateException(
                        "Incomplete hopper transport pair for action id=" + a.id()
                            + " pairId=" + a.pairId());
                    LOG.warn("RollbackEngine: refusing lone hopper pair member id={} pairId={}",
                        a.id(), a.pairId());
                    outcomes.add(WorldMutationResult.failed(a.id(), failure));
                } else {
                    try {
                        outcomes.add(applyMutation(a, mode));
                    } catch (RuntimeException thrown) {
                        // A mutator that throws mid-batch leaves the world partly
                        // changed. Everything still unapplied in this batch is a
                        // repair gap, not a clean failure — otherwise a half-applied
                        // explosion vanishes from vg_repair_required entirely.
                        LOG.warn("RollbackEngine.{}: mutation threw at action id={}",
                            mode, a.id(), thrown);
                        outcomes.add(WorldMutationResult.repairRequired(a.id(), a.pairId(), thrown));
                        for (int j = i + 1; j < batch.size(); j++) {
                            if (done.add(j)) {
                                outcomes.add(WorldMutationResult.repairRequired(
                                    batch.get(j).id(), batch.get(j).pairId(), thrown));
                            }
                        }
                        return outcomes;
                    }
                }
            }
        }
        return outcomes;
    }

    private List<WorldMutationResult> applyInventoryPair(Action a, Action b, RollbackResult.Mode mode) {
        Action withdraw = a.type() == ActionType.INVENTORY_WITHDRAW ? a : b;
        Action deposit = a.type() == ActionType.INVENTORY_DEPOSIT ? a : b;
        Action first = mode == RollbackResult.Mode.ROLLBACK ? deposit : withdraw;
        Action second = mode == RollbackResult.Mode.ROLLBACK ? withdraw : deposit;

        WorldMutationResult firstResult = applyMutation(first, mode);
        if (firstResult.status() != WorldMutationResult.Status.APPLIED) {
            Throwable cause = firstResult.failure() != null ? firstResult.failure()
                : new IllegalStateException("inventory pair first half not applied: " + firstResult.status());
            if (firstResult.status() == WorldMutationResult.Status.REPAIR_REQUIRED) {
                return List.of(firstResult, WorldMutationResult.repairRequired(second.id(), second.pairId(),
                    new IllegalStateException("inventory pair mate repair-required for action id=" + first.id(), cause)));
            }
            WorldMutationResult firstOut = firstResult.status() == WorldMutationResult.Status.FAILED
                ? firstResult : WorldMutationResult.failed(first.id(), cause);
            return List.of(firstOut, WorldMutationResult.failed(second.id(),
                new IllegalStateException("inventory pair mate failed for action id=" + first.id(), cause)));
        }

        WorldMutationResult secondResult = applyMutation(second, mode);
        if (secondResult.status() != WorldMutationResult.Status.APPLIED) {
            WorldMutationResult compensation = compensateInventoryHalf(first, mode);
            Throwable cause = secondResult.failure() != null ? secondResult.failure()
                : new IllegalStateException("inventory pair second half not applied: " + secondResult.status());
            if (compensation.status() == WorldMutationResult.Status.APPLIED) {
                return List.of(
                    WorldMutationResult.compensated(first.id(), new IllegalStateException(
                        "inventory pair compensated=true after mate failure", cause)),
                    secondResult.status() == WorldMutationResult.Status.FAILED
                        ? secondResult : WorldMutationResult.failed(second.id(), cause));
            }
            IllegalStateException repair = new IllegalStateException(
                    "inventory pair compensation failed; world may remain half-mutated"
                            + " firstId=" + first.id() + " secondId=" + second.id(),
                    compensation.failure() != null ? compensation.failure() : cause);
            LOG.error("RollbackEngine: inventory pair compensation failed for action id={} after mate id={}; "
                    + "persisting repair-required (world may remain half-mutated)",
                    first.id(), second.id());
            WorldMutationResult firstRepair = WorldMutationResult.repairRequired(first.id(), first.pairId(), repair);
            WorldMutationResult secondRepair = secondResult.status() == WorldMutationResult.Status.REPAIR_REQUIRED
                    ? secondResult : WorldMutationResult.repairRequired(second.id(), second.pairId(), repair);
            return List.of(firstRepair, secondRepair);
        }
        return List.of(firstResult, secondResult);
    }

    private List<WorldMutationResult> applyHopperPair(Action a, Action b, RollbackResult.Mode mode) {
        Action pull = a.type() == ActionType.HOPPER_PULL ? a : b;
        Action push = a.type() == ActionType.HOPPER_PUSH ? a : b;
        Action first = mode == RollbackResult.Mode.ROLLBACK ? push : pull;
        Action second = mode == RollbackResult.Mode.ROLLBACK ? pull : push;

        WorldMutationResult firstResult = applyMutation(first, mode);
        if (firstResult.status() != WorldMutationResult.Status.APPLIED) {
            Throwable cause = firstResult.failure() != null ? firstResult.failure()
                : new IllegalStateException("hopper pair first half not applied: " + firstResult.status());
            if (firstResult.status() == WorldMutationResult.Status.REPAIR_REQUIRED) {
                return List.of(firstResult, WorldMutationResult.repairRequired(second.id(), second.pairId(),
                    new IllegalStateException("hopper pair mate repair-required for action id=" + first.id(), cause)));
            }
            WorldMutationResult firstOut = firstResult.status() == WorldMutationResult.Status.FAILED
                ? firstResult : WorldMutationResult.failed(first.id(), cause);
            return List.of(firstOut, WorldMutationResult.failed(second.id(),
                new IllegalStateException("hopper pair mate failed for action id=" + first.id(), cause)));
        }

        WorldMutationResult secondResult = applyMutation(second, mode);
        if (secondResult.status() != WorldMutationResult.Status.APPLIED) {
            WorldMutationResult compensation = compensateHopperHalf(first, mode);
            Throwable cause = secondResult.failure() != null ? secondResult.failure()
                : new IllegalStateException("hopper pair second half not applied: " + secondResult.status());
            if (compensation.status() == WorldMutationResult.Status.APPLIED) {
                return List.of(
                    WorldMutationResult.compensated(first.id(), new IllegalStateException(
                        "hopper pair compensated=true after mate failure", cause)),
                    secondResult.status() == WorldMutationResult.Status.FAILED
                        ? secondResult : WorldMutationResult.failed(second.id(), cause));
            }
            IllegalStateException repair = new IllegalStateException(
                    "hopper pair compensation failed; world may remain half-mutated"
                            + " firstId=" + first.id() + " secondId=" + second.id(),
                    compensation.failure() != null ? compensation.failure() : cause);
            LOG.error("RollbackEngine: hopper pair compensation failed for action id={} after mate id={}; "
                    + "persisting repair-required (world may remain half-mutated)",
                    first.id(), second.id());
            WorldMutationResult firstRepair = WorldMutationResult.repairRequired(first.id(), first.pairId(), repair);
            WorldMutationResult secondRepair = secondResult.status() == WorldMutationResult.Status.REPAIR_REQUIRED
                    ? secondResult : WorldMutationResult.repairRequired(second.id(), second.pairId(), repair);
            return List.of(firstRepair, secondRepair);
        }
        return List.of(firstResult, secondResult);
    }

    private WorldMutationResult compensateHopperHalf(Action applied, RollbackResult.Mode mode) {
        RollbackResult.Mode inverse = mode == RollbackResult.Mode.ROLLBACK
            ? RollbackResult.Mode.RESTORE : RollbackResult.Mode.ROLLBACK;
        return applyMutation(applied, inverse);
    }

    private WorldMutationResult compensateInventoryHalf(Action applied, RollbackResult.Mode mode) {
        RollbackResult.Mode inverse = mode == RollbackResult.Mode.ROLLBACK
            ? RollbackResult.Mode.RESTORE : RollbackResult.Mode.ROLLBACK;
        return applyMutation(applied, inverse);
    }

    private WorldMutationResult applyMutation(Action action, RollbackResult.Mode mode) {
        try {
            // Capture the mutator boolean. false is not APPLIED: no markRolledBack,
            // action id stays out of confirmed IDs, audit batch remains open.
            // Success is the mutation API result only — runtime read-back is a
            // separate gap and is not claimed here.
            boolean ok = mode == RollbackResult.Mode.ROLLBACK
                ? applyInverse(action)
                : applyForward(action);
            if (!ok) {
                IllegalStateException failure = new IllegalStateException(
                    "WorldMutator reported unsuccessful mutation for action id="
                        + action.id() + " type=" + action.type());
                // Name the row's own data. "mutation unsuccessful for id=14356372" left
                // an operator with 257 identical lines and no way to tell whether the
                // container was missing, full, or holding different items - which are
                // three different problems with three different fixes.
                LOG.warn("RollbackEngine: mutation unsuccessful for action id={} type={} "
                        + "at {} {},{},{} target={} amount={} slot={}",
                    action.id(), action.type(), action.worldId(),
                    action.x(), action.y(), action.z(),
                    action.targetId(), action.amount(), action.inventorySlot());
                return WorldMutationResult.failed(action.id(), failure);
            }
            return WorldMutationResult.applied(action.id());
        } catch (UncompensatedSlotMutationException failure) {
            LOG.error("RollbackEngine: uncompensated exact-slot mutation for action id={} type={}; "
                    + "persisting repair-required",
                    action.id(), action.type(), failure);
            return WorldMutationResult.repairRequired(action.id(), action.pairId(), failure);
        } catch (PartialExplosionMutationException failure) {
            // Some affected blocks were written and the rest were not. A FAILED
            // status here would tell the operator nothing changed, which is
            // false; the blocks that did change must be recorded as needing
            // repair so they can be found again.
            LOG.error("RollbackEngine: partial explosion mutation for action id={} ({} of {} blocks); "
                    + "persisting repair-required",
                    action.id(), failure.applied(), failure.total(), failure);
            return WorldMutationResult.repairRequired(action.id(), action.pairId(), failure);
        } catch (Throwable failure) {
            LOG.warn("RollbackEngine: mutation failed for action id={} type={} ({})",
                action.id(), action.type(), failure.toString());
            return WorldMutationResult.failed(action.id(), failure);
        }
    }

    /** Apply the inverse of the action (used by rollback). @return mutator success */
    private boolean applyInverse(Action a) {
        // v1.3.2 Y1: branch on a.hasNbt() and route through the NBT-aware
        // WorldMutator overloads when the row carries any NBT fidelity payload.
        // Loader checked methods fail closed on decode, registry, or apply failure;
        // the compatibility defaults never claim checked success for a void bridge.
        // When hasNbt()==false we skip the NBT overload entirely so the hot path
        // stays allocation-free for pre-v1.3.1 rows.
        boolean nbt = a.hasNbt();
        return switch (a.type()) {
            // REDSTONE_TOGGLE is never dispatched (isRollbackable returns false), but
            // the switch is exhaustive over ActionType, so it must be named. Returning
            // false keeps the contract: this hook did not mutate anything.
            case REDSTONE_TOGGLE -> false;
            case BLOCK_PLACE ->
                mutator.tryClearBlock(a.worldId(), a.x(), a.y(), a.z());
            case FARMLAND_TRAMPLE, BLOCK_TRANSFORM -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(),
                        a.targetMeta(), a.oldBlockState(), a.blockEntityNbt());
                }
                yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), a.targetMeta());
            }
            case BLOCK_BREAK -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                        a.oldBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            case CONTAINER_DEPOSIT -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            }
            case CONTAINER_WITHDRAW -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryAddToContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case ITEM_DROP -> {
                // Rolling back a drop = the dropped stack never existed. Remove
                // the item entity. tryRemoveEntity re-checks the entity type, so
                // a mismatched entity at that position is not destroyed.
                yield mutator.tryRemoveEntity(a.worldId(), a.x(), a.y(), a.z(), ITEM_ENTITY_TYPE);
            }
            case ITEM_PICKUP -> {
                // Rolling back a pickup = the player never got the stack. Give it
                // back at the pickup position, with the recorded item NBT so the
                // stack is byte-identical rather than a lookalike.
                if (a.targetId() == null) {
                    LOG.warn("RollbackEngine: refusing to roll back ITEM_PICKUP (id={}) — item id missing", a.id());
                    yield false;
                }
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case ENTITY_KILL -> {
                if (nbt) {
                    yield mutator.tryRespawnEntity(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), a.targetMeta(), a.entityNbt());
                } else {
                    yield mutator.tryRespawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            case EXPLOSION ->
                restoreExplosion(a);
            // --- v0.1.0 expansion: block events ---
            // ENTITY_CHANGE_BLOCK: targetId carries oldBlockId; targetMeta carries newBlockId.
            case ENTITY_CHANGE_BLOCK -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), null,
                        a.oldBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), null);
                }
            }
            // Block was destroyed/changed-away — inverse is to restore the original block.
            case BURN, FADE, LEAVES_DECAY, BUCKET_FILL -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                        a.oldBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            // Block was created — inverse is to clear it.
            case IGNITE, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW ->
                mutator.tryClearBlock(a.worldId(), a.x(), a.y(), a.z());
            // --- v0.1.0 expansion: containers ---
            case HOPPER_PUSH -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            }
            case HOPPER_PULL -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryAddToContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            // --- v0.1.0 expansion: entities ---
            case HANGING_PLACE ->
                mutator.tryRemoveEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId());
            case HANGING_BREAK -> {
                if (nbt) {
                    yield mutator.tryRespawnEntity(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), a.targetMeta(), a.entityNbt());
                } else {
                    yield mutator.tryRespawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            // --- per-action explicit refusals (replacing the silent default branch) ---
            case DISPENSE -> {
                LOG.warn("RollbackEngine: refusing to roll back DISPENSE (id={}) — container slot tracking required", a.id());
                yield false;
            }
            case FORM, SPREAD -> {
                LOG.warn("RollbackEngine: refusing to roll back {} (id={}) — old replacement state not tracked", a.type(), a.id());
                yield false;
            }
            case PISTON_EXTEND, PISTON_RETRACT -> {
                LOG.warn("RollbackEngine: refusing to roll back {} (id={}) — source position not tracked", a.type(), a.id());
                yield false;
            }
            case INVENTORY_DEPOSIT -> {
                if (a.actorUuid() == null) {
                    LOG.warn("RollbackEngine: refusing INVENTORY_DEPOSIT (id={}) — actor UUID missing", a.id());
                    yield false;
                }
                if (a.itemNbt() == null) {
                    LOG.warn("RollbackEngine: refusing inventory operation (id={}) — full item NBT payload missing", a.id());
                    yield false;
                }
                yield mutator.tryRemoveFromPlayerInventory(a.actorUuid(), a.targetId(),
                    Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
            }
            case INVENTORY_WITHDRAW -> {
                if (a.actorUuid() == null) {
                    LOG.warn("RollbackEngine: refusing INVENTORY_WITHDRAW (id={}) — actor UUID missing", a.id());
                    yield false;
                }
                if (a.itemNbt() == null) {
                    LOG.warn("RollbackEngine: refusing inventory operation (id={}) — full item NBT payload missing", a.id());
                    yield false;
                }
                yield mutator.tryAddToPlayerInventory(a.actorUuid(), a.targetId(),
                    Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
            }
            case ITEM_CRAFT -> {
                LOG.warn("RollbackEngine: refusing to roll back ITEM_CRAFT (id={}) — inventory state required", a.id());
                yield false;
            }
            case ENTITY_SPAWN -> {
                LOG.warn("RollbackEngine: refusing to roll back ENTITY_SPAWN (id={}) — despawn unsafe", a.id());
                yield false;
            }
            case ENTITY_INTERACT -> {
                LOG.warn("RollbackEngine: refusing to roll back ENTITY_INTERACT (id={}) — no state change to undo", a.id());
                yield false;
            }
            case CHUNK_POPULATE -> {
                LOG.warn("RollbackEngine: refusing to roll back CHUNK_POPULATE (id={}) — chunk-scale revert unsafe", a.id());
                yield false;
            }
            case CLICK -> {
                LOG.warn("RollbackEngine: refusing to roll back CLICK (id={}) — audit-only, no state change", a.id());
                yield false;
            }
            case CHAT, COMMAND, SIGN, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE -> {
                LOG.warn("RollbackEngine: refusing to roll back non-rollbackable {} (id={})", a.type(), a.id());
                yield false;
            }
        };
    }

    /** Reapply the original action (used by restore). @return mutator success */
    private boolean applyForward(Action a) {
        // v1.3.2 Y1: mirror applyInverse's NBT branching. Restore semantics
        // re-apply the row's original mutation, so the NBT payload used here is
        // the "new state" side (post-change) — newBlockState + blockEntityNbt
        // for a BLOCK_PLACE, itemNbt for CONTAINER_DEPOSIT / ITEM_DROP /
        // HOPPER_PUSH, entityNbt for HANGING_PLACE.
        boolean nbt = a.hasNbt();
        return switch (a.type()) {
            case BLOCK_PLACE -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                        a.newBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            case REDSTONE_TOGGLE -> false;
            // Restoring a break re-applies the destruction: clear the position the
            // block was broken from. This case was dropped during the 2.3.0 type
            // additions and the compiler caught it as a missing exhaustive branch -
            // which is exactly why the switches are exhaustive.
            case BLOCK_BREAK -> mutator.tryClearBlock(a.worldId(), a.x(), a.y(), a.z());
            case CONTAINER_DEPOSIT -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryAddToContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case CONTAINER_WITHDRAW -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            }
            case ITEM_DROP -> {
                // Restoring a drop = re-create the dropped stack at the recorded
                // position, with its item NBT so the re-dropped item is identical.
                if (a.targetId() == null) {
                    LOG.warn("RollbackEngine: refusing to restore ITEM_DROP (id={}) — item id missing", a.id());
                    yield false;
                }
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case ITEM_PICKUP -> {
                // Restoring a pickup = the player picked the stack up again, so
                // the stack must leave their inventory. Fail closed if the actor
                // is unknown rather than removing items from the wrong player.
                if (a.actorUuid() == null) {
                    LOG.warn("RollbackEngine: refusing to restore ITEM_PICKUP (id={}) — actor UUID missing", a.id());
                    yield false;
                }
                if (a.targetId() == null) {
                    LOG.warn("RollbackEngine: refusing to restore ITEM_PICKUP (id={}) — item id missing", a.id());
                    yield false;
                }
                yield mutator.tryRemoveFromPlayerInventory(a.actorUuid(), a.targetId(),
                    Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
            }
            case ENTITY_KILL -> {
                // Restoring a kill is a documented no-op, and
                // RollbackPlan.rejectionReason now excludes it at plan time, so
                // this arm is unreachable by construction. It stays because the
                // switch must be exhaustive; it must never be the place a
                // restore silently reports a skip.
                LOG.debug("RollbackEngine: restore of ENTITY_KILL id={} is best-effort no-op", a.id());
                yield true;
            }
            case EXPLOSION ->
                clearExplosionBlocks(a);
            // --- v0.1.0 expansion: block events ---
            // ENTITY_CHANGE_BLOCK: re-apply the newBlockId carried in targetMeta.
            case ENTITY_CHANGE_BLOCK -> {
                String newId = a.targetMeta() != null ? a.targetMeta() : AIR;
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), newId, null,
                        a.newBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), newId, null);
                }
            }
            // Block was originally destroyed/changed-away — restoring means re-destroying.
            // BLOCK_TRANSFORM is a non-air to non-air change, so "restoring" puts the
            // previous state back rather than clearing to air.
            case BLOCK_TRANSFORM -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(),
                        a.targetMeta(), a.oldBlockState(), a.blockEntityNbt());
                }
                yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), a.targetMeta());
            }
            case FARMLAND_TRAMPLE, BURN, FADE, LEAVES_DECAY, BUCKET_FILL ->
                mutator.tryClearBlock(a.worldId(), a.x(), a.y(), a.z());
            // Block was originally created — restoring means re-placing it.
            case IGNITE, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                        a.newBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            // --- v0.1.0 expansion: containers ---
            case HOPPER_PUSH -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryAddToContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case HOPPER_PULL -> {
                if (a.inventorySlot() != null) {
                    yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
                }
                yield mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            }
            // --- v0.1.0 expansion: entities ---
            case HANGING_PLACE -> {
                if (nbt) {
                    yield mutator.tryRespawnEntity(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), a.targetMeta(), a.entityNbt());
                } else {
                    yield mutator.tryRespawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            case HANGING_BREAK ->
                mutator.tryRemoveEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId());
            // --- per-action explicit refusals (replacing the silent default branch) ---
            case DISPENSE -> {
                LOG.warn("RollbackEngine: refusing to restore DISPENSE (id={}) — container slot tracking required", a.id());
                yield false;
            }
            case FORM, SPREAD -> {
                LOG.warn("RollbackEngine: refusing to restore {} (id={}) — old replacement state not tracked", a.type(), a.id());
                yield false;
            }
            case PISTON_EXTEND, PISTON_RETRACT -> {
                LOG.warn("RollbackEngine: refusing to restore {} (id={}) — source position not tracked", a.type(), a.id());
                yield false;
            }
            case INVENTORY_DEPOSIT -> {
                if (a.actorUuid() == null) {
                    LOG.warn("RollbackEngine: refusing to restore INVENTORY_DEPOSIT (id={}) — actor UUID missing", a.id());
                    yield false;
                }
                if (a.itemNbt() == null) {
                    LOG.warn("RollbackEngine: refusing inventory operation (id={}) — full item NBT payload missing", a.id());
                    yield false;
                }
                yield mutator.tryAddToPlayerInventory(a.actorUuid(), a.targetId(),
                    Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
            }
            case INVENTORY_WITHDRAW -> {
                if (a.actorUuid() == null) {
                    LOG.warn("RollbackEngine: refusing to restore INVENTORY_WITHDRAW (id={}) — actor UUID missing", a.id());
                    yield false;
                }
                if (a.itemNbt() == null) {
                    LOG.warn("RollbackEngine: refusing inventory operation (id={}) — full item NBT payload missing", a.id());
                    yield false;
                }
                yield mutator.tryRemoveFromPlayerInventory(a.actorUuid(), a.targetId(),
                    Math.max(1, a.amount()), a.targetMeta(), a.itemNbt(), a.inventorySlot());
            }
            case ITEM_CRAFT -> {
                LOG.warn("RollbackEngine: refusing to restore ITEM_CRAFT (id={}) — inventory state required", a.id());
                yield false;
            }
            case ENTITY_SPAWN -> {
                LOG.warn("RollbackEngine: refusing to restore ENTITY_SPAWN (id={}) — despawn unsafe", a.id());
                yield false;
            }
            case ENTITY_INTERACT -> {
                LOG.warn("RollbackEngine: refusing to restore ENTITY_INTERACT (id={}) — no state change to redo", a.id());
                yield false;
            }
            case CHUNK_POPULATE -> {
                LOG.warn("RollbackEngine: refusing to restore CHUNK_POPULATE (id={}) — chunk-scale revert unsafe", a.id());
                yield false;
            }
            case CLICK -> {
                LOG.warn("RollbackEngine: refusing to restore CLICK (id={}) — audit-only, no state change", a.id());
                yield false;
            }
            case CHAT, COMMAND, SIGN, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE -> {
                LOG.warn("RollbackEngine: refusing to restore non-rollbackable {} (id={})", a.type(), a.id());
                yield false;
            }
        };
    }

    /**
     * Restore every pre-blast block state for an explosion row.
     *
     * <p>A blast is one audit row but N block mutations. If some of those blocks
     * are written and others fail, the row is neither "applied" nor "failed" —
     * the world is in a state nobody chose. That is exactly
     * {@link WorldMutationResult.Status#REPAIR_REQUIRED}, and it must be
     * persisted: reporting {@code FAILED} for a half-restored crater tells the
     * operator nothing changed, which is false and leaves them unable to find
     * the blocks that did.</p>
     *
     * @return {@code true} only when every affected block was restored
     * @throws PartialExplosionMutationException when some but not all blocks
     *         were written, carrying the count so the repair row can name it
     */
    private boolean restoreExplosion(Action a) {
        boolean hasSidecar = a.blockEntityNbt() != null && a.blockEntityNbt().length > 0;
        ExplosionAffectedList list = hasSidecar
            ? ExplosionAffectedList.parse(a.targetId(), a.blockEntityNbt())
            : ExplosionAffectedList.parse(a.targetId());
        if (list.isEmpty()) return false;
        int applied = 0;
        int total = 0;
        boolean allOk = true;
        for (ExplosionAffectedList.Entry e : list.entries()) {
            total++;
            // Restore the pre-blast block state at each affected coord. Legacy
            // inline meta remains targetMeta; sidecar meta is v5 block-state props.
            boolean ok;
            try {
                if (hasSidecar && (e.meta() != null || e.blockEntityNbt() != null)) {
                    ok = mutator.trySetBlock(a.worldId(), e.x(), e.y(), e.z(), e.blockId(), null,
                        e.meta(), e.blockEntityNbt());
                } else {
                    ok = mutator.trySetBlock(a.worldId(), e.x(), e.y(), e.z(), e.blockId(), e.meta());
                }
            } catch (RuntimeException thrown) {
                // A loader mutator can throw mid-explosion (chunk unloaded, world
                // locked). The blocks already written stay written, so this is a
                // partial mutation and must be reported as one rather than swallowed
                // by the generic failure path — otherwise a half-restored blast looks
                // like nothing happened and never reaches vg_repair_required.
                // total is the row's full block count, not the iteration counter: the
                // operator needs "1 of 3" (what this row describes), not "1 of 2"
                // (how far the loop got).
                throw new PartialExplosionMutationException(
                    "explosion row id=" + a.id() + " threw after restoring " + applied + " of "
                        + list.entries().size() + " block(s): " + thrown,
                    applied, list.entries().size(), thrown);
            }
            if (ok) {
                applied++;
            } else {
                allOk = false;
                LOG.debug("RollbackEngine: explosion row id={} could not restore {} of {} "
                        + "block(s) at {},{},{} — usually a position another mod or a "
                        + "player already changed", a.id(), list.entries().size() - applied,
                    list.entries().size(), e.x(), e.y(), e.z());
            }
        }
        // An explosion touches up to ~96 positions in one row. A handful that will
        // not place — a block another mod claimed, a position a player already
        // refilled, a fluid that immediately flows back — is normal. Demanding 96/96
        // turned every large blast into REPAIR_REQUIRED and left the batch open, so
        // a rollback of a serious griefing incident could never report success.
        //
        // Only a genuinely bad outcome is escalated: nothing restored at all, or a
        // large share of the row unaccounted for.
        if (!allOk) {
            int total_ = list.entries().size();
            int failed_ = total_ - applied;
            if (applied == 0) {
                // Nothing changed at all: this is an ordinary failure, not a repair
                // gap. The row can be retried as-is and there is no half-restored
                // state for an operator to reconcile.
                return false;
            }
            boolean severe = total_ > 0
                && failed_ * 100L / total_ > SEVERE_EXPLOSION_FAILURE_PERCENT;
            if (severe) {
                throw new PartialExplosionMutationException(
                    "explosion row id=" + a.id() + " restored only " + applied + " of " + total_
                        + " block(s); " + failed_ + " position(s) still carry blast damage",
                    applied, total_);
            }
            LOG.warn("RollbackEngine: explosion row id={} restored {}/{} block(s); {} position(s) "
                    + "are already in a different state and were left alone",
                a.id(), applied, total_, failed_);
        }
        return true;
    }

    /**
     * Clear every affected block for a restore of an explosion row.
     *
     * @return {@code true} only when every affected block was cleared
     * @throws PartialExplosionMutationException when some but not all blocks
     *         were cleared
     */
    private boolean clearExplosionBlocks(Action a) {
        ExplosionAffectedList list = ExplosionAffectedList.parse(a.targetId());
        if (list.isEmpty()) return false;
        int applied = 0;
        int total = 0;
        boolean allOk = true;
        for (ExplosionAffectedList.Entry e : list.entries()) {
            total++;
            // Restore direction: re-clear the affected area (re-apply the blast).
            if (mutator.tryClearBlock(a.worldId(), e.x(), e.y(), e.z())) {
                applied++;
            } else {
                allOk = false;
            }
        }
        if (!allOk && applied > 0) {
            throw new PartialExplosionMutationException(
                "explosion row id=" + a.id() + " re-cleared " + applied + " of " + total
                    + " block(s); the remaining coords were left untouched",
                applied, total);
        }
        return allOk;
    }

    /** For tests + internal use only. */
    static boolean isRollbackable(ActionType t) {
        if (t == null) return false;
        return switch (t) {
            case BLOCK_PLACE, BLOCK_BREAK,
                 CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
                 ENTITY_KILL, EXPLOSION,
                 BURN, IGNITE, FADE, LEAVES_DECAY,
                 BUCKET_EMPTY, BUCKET_FILL, ENTITY_CHANGE_BLOCK,
                 HOPPER_PUSH, HOPPER_PULL,
                 HANGING_PLACE, HANGING_BREAK,
                 STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW,
                 INVENTORY_DEPOSIT, INVENTORY_WITHDRAW,
                 ITEM_DROP, ITEM_PICKUP,
                 FARMLAND_TRAMPLE,
                 BLOCK_TRANSFORM -> true;
            case CHAT, COMMAND, SIGN,
                 SESSION_JOIN, SESSION_LEAVE,
                 USERNAME_CHANGE,
                 DISPENSE, PISTON_EXTEND, PISTON_RETRACT,
                 FORM, SPREAD,
                 ITEM_CRAFT,
                 ENTITY_SPAWN, ENTITY_INTERACT,
                 CHUNK_POPULATE, CLICK -> false;
            // REDSTONE_TOGGLE is evidence, not state: flipping a lever back can start
            // a machine the operator was trying to stop.
            case REDSTONE_TOGGLE -> false;
        };
    }

    /**
     * Types that never recorded a world change, so there is nothing to undo and
     * skipping them is correct rather than a limitation. Everything else that is
     * not {@link #isRollbackable(ActionType)} is a real world change this build
     * cannot invert because it does not record the prior state.
     *
     * <p>Splitting these two keeps {@code skipped=N} honest: "126 chat lines"
     * and "126 dispenser events" mean very different things to an operator.</p>
     */
    static boolean isAuditOnly(ActionType t) {
        if (t == null) return false;
        return switch (t) {
            case CHAT, COMMAND, SIGN,
                 SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE,
                 CLICK -> true;
            default -> false;
        };
    }
}
