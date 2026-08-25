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

    /** Max mutations dispatched per server tick. */
    public static final int BATCH_SIZE = 1000;

    /** Page size for {@link #fetchMatches}. */
    static final int PAGE_SIZE = 5000;

    /** Inert block id used to clear a {@code BLOCK_PLACE}. */
    static final String AIR = "minecraft:air";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Default reach (block-radius) padding for the W5 supplemental EXPLOSION scan.
     * Vanilla TNT clears roughly a 7-block radius; 16 gives comfortable headroom
     * without unbounding the scan. Overridable via
     * {@link network.vonix.guardian.core.config.GuardianConfig.Rollback#explosionSupplementalReach}.
     * @since 1.3.1 X8
     */
    public static final int MAX_TNT_REACH = 16;

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
        return executeAsync(plan(filter, RollbackResult.Mode.ROLLBACK, actorUuid, options), preview);
    }

    /** Non-blocking restore entry point with actor and safety controls. */
    public CompletionStage<RollbackResult> restoreAsync(QueryFilter filter, boolean preview,
                                                         UUID actorUuid,
                                                         RollbackOptions options) throws Exception {
        return executeAsync(plan(filter, RollbackResult.Mode.RESTORE, actorUuid, options), preview);
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
        return execute(plan(filter, RollbackResult.Mode.ROLLBACK, actorUuid, options), preview);
    }

    /** Execute restore with explicit actor + large-job safety controls. */
    public RollbackResult restore(QueryFilter filter, boolean preview, UUID actorUuid, RollbackOptions options) throws Exception {
        return execute(plan(filter, RollbackResult.Mode.RESTORE, actorUuid, options), preview);
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
        try {
            return executeAsync(plan, preview).toCompletableFuture().join();
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

    /**
     * Execute a plan without blocking for server-thread work. A non-preview
     * stage is completed only after confirmed mutation outcomes have been
     * persisted and the rollback batch has been closed.
     */
    public CompletionStage<RollbackResult> executeAsync(RollbackPlan plan, boolean preview) throws Exception {
        Objects.requireNonNull(plan, "plan");
        RollbackResult.Mode mode = plan.mode();
        if (mode == null) {
            throw new IllegalArgumentException(
                "RollbackPlan has no mode — use RollbackEngine.plan(...) to build it");
        }
        UUID actorUuid = plan.actorUuid();
        QueryFilter effective = plan.originalFilter();
        List<Long> affectedIds = plan.actionIds();
        List<Long> skippedIds = plan.skippedIds();
        int planned = plan.plannedSteps();

        if (!plan.skipped().isEmpty()) {
            LOG.warn("RollbackEngine.{}: {} action(s) excluded as not rollbackable",
                mode, plan.skipped().size());
        }

        if (preview || planned == 0) {
            return CompletableFuture.completedFuture(new RollbackResult(actorUuid, mode, preview,
                affectedIds, skippedIds, planned, 0, effective));
        }

        String filterJson = encodeFilter(effective);
        long batchId = dao.openRollbackBatch(actorUuid, mode.ordinal(), filterJson, affectedIds);

        CompletionStage<List<WorldMutationResult>> mutations;
        try {
            mutations = dispatchBatches(plan.ordered(), mode);
        } catch (RuntimeException e) {
            LOG.error("RollbackEngine.{}: batch id={} failed during dispatch; vg_rollback_batches row left OPEN for recovery",
                mode, batchId, e);
            return failedStage(new RollbackMutationException(batchId, List.of(
                WorldMutationResult.failed(-1L, e))));
        }
        return finalizeAsync(mutations, batchId, actorUuid, mode, effective, skippedIds, planned);
    }

    private CompletionStage<RollbackResult> finalizeAsync(CompletionStage<List<WorldMutationResult>> mutations,
                                                            long batchId,
                                                            UUID actorUuid,
                                                            RollbackResult.Mode mode,
                                                            QueryFilter effective,
                                                            List<Long> plannedSkippedIds,
                                                            int planned) {
        CompletableFuture<RollbackResult> result = new CompletableFuture<>();
        mutations.whenComplete((outcomes, failure) -> executeCompletion(() -> {
            try {
                if (failure != null) {
                    Throwable cause = unwrapCompletionFailure(failure);
                    LOG.error("RollbackEngine.{}: batch id={} failed mid-flight; vg_rollback_batches row left OPEN for recovery",
                        mode, batchId, cause);
                    result.completeExceptionally(new RollbackMutationException(batchId, List.of(
                        WorldMutationResult.failed(-1L, cause))));
                    return;
                }
                result.complete(finishBatch(batchId, actorUuid, mode, effective,
                    plannedSkippedIds, planned, outcomes));
            } catch (Throwable finalizationFailure) {
                result.completeExceptionally(finalizationFailure);
            }
        }, "batch finalization"));
        return result;
    }

    private RollbackResult finishBatch(long batchId,
                                       UUID actorUuid,
                                       RollbackResult.Mode mode,
                                       QueryFilter effective,
                                       List<Long> plannedSkippedIds,
                                       int planned,
                                       List<WorldMutationResult> outcomes) {
        List<Long> appliedIds = new ArrayList<>();
        List<WorldMutationResult> incomplete = new ArrayList<>();
        List<WorldMutationResult> repairRequired = new ArrayList<>();
        for (WorldMutationResult outcome : outcomes) {
            if (outcome.status() == WorldMutationResult.Status.APPLIED) {
                appliedIds.add(outcome.actionId());
            } else {
                incomplete.add(outcome);
                if (outcome.status() == WorldMutationResult.Status.REPAIR_REQUIRED) {
                    repairRequired.add(outcome);
                }
            }
        }

        boolean targetFlag = mode == RollbackResult.Mode.ROLLBACK;
        if (!incomplete.isEmpty()) {
            if (!appliedIds.isEmpty()) {
                markApplied(batchId, appliedIds, targetFlag);
            }
            if (!repairRequired.isEmpty()) {
                persistRepairRequired(batchId, repairRequired);
            }
            LOG.error("RollbackEngine.{}: batch id={} incomplete; vg_rollback_batches row left OPEN for recovery",
                mode, batchId);
            throw new RollbackMutationException(batchId, incomplete);
        }

        markApplied(batchId, appliedIds, targetFlag);
        try {
            int closed = dao.closeRollbackBatch(batchId);
            if (closed != 1) {
                LOG.error("RollbackEngine.{}: closeRollbackBatch id={} updated {} rows (expected 1); "
                        + "audit row considered unclosed", mode, batchId, closed);
                throw new IllegalStateException(
                        "closeRollbackBatch updated " + closed + " rows for batch id=" + batchId
                                + " (expected 1); audit row left unclosed");
            }
        } catch (Exception e) {
            LOG.error("RollbackEngine.{}: failed to close batch id={}", mode, batchId, e);
            throw new CompletionException(e);
        }

        return new RollbackResult(actorUuid, mode, false,
            appliedIds, plannedSkippedIds, planned, appliedIds.size(), effective);
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
     * (server crashed mid-rollback) and log a WARN per affected action so
     * operators can decide whether to re-run rollback or accept the state.
     */
    public void recoverIncompleteBatches() throws Exception {
        List<Long> ids = dao.findIncompleteBatchActionIds();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        LOG.warn("RollbackEngine: found {} action(s) in incomplete rollback batches — operator review required",
            ids.size());
        for (Long id : ids) {
            LOG.warn("RollbackEngine: incomplete batch contains action id={} (use /vg lookup to inspect)", id);
        }
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
        while (cursor < ordered.size() && batch.size() < BATCH_SIZE) {
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

    private static void appendFailed(List<WorldMutationResult> outcomes,
                                     List<Action> ordered,
                                     int start,
                                     Throwable failure) {
        for (int i = start; i < ordered.size(); i++) {
            outcomes.add(WorldMutationResult.failed(ordered.get(i).id(), failure));
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
                outcomes.add(applyMutation(a, mode));
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
                    WorldMutationResult.failed(first.id(), new IllegalStateException(
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

    private WorldMutationResult compensateInventoryHalf(Action applied, RollbackResult.Mode mode) {
        RollbackResult.Mode inverse = mode == RollbackResult.Mode.ROLLBACK
            ? RollbackResult.Mode.RESTORE : RollbackResult.Mode.ROLLBACK;
        return applyMutation(applied, inverse);
    }

    private WorldMutationResult applyMutation(Action action, RollbackResult.Mode mode) {
        if (isUnsupportedAtDispatch(action, mode)) {
            return WorldMutationResult.skipped(action.id());
        }
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
                LOG.warn("RollbackEngine: mutation unsuccessful for action id={} type={}",
                    action.id(), action.type());
                return WorldMutationResult.failed(action.id(), failure);
            }
            return WorldMutationResult.applied(action.id());
        } catch (UncompensatedSlotMutationException failure) {
            LOG.error("RollbackEngine: uncompensated exact-slot mutation for action id={} type={}; "
                    + "persisting repair-required",
                    action.id(), action.type(), failure);
            return WorldMutationResult.repairRequired(action.id(), action.pairId(), failure);
        } catch (Throwable failure) {
            LOG.warn("RollbackEngine: mutation failed for action id={} type={} ({})",
                action.id(), action.type(), failure.toString());
            return WorldMutationResult.failed(action.id(), failure);
        }
    }

    private static boolean isUnsupportedAtDispatch(Action action, RollbackResult.Mode mode) {
        if (mode == RollbackResult.Mode.RESTORE && action.type() == ActionType.ENTITY_KILL) {
            return true;
        }
        if (action.type() == ActionType.EXPLOSION) {
            boolean hasSidecar = action.blockEntityNbt() != null && action.blockEntityNbt().length > 0;
            return (hasSidecar
                ? ExplosionAffectedList.parse(action.targetId(), action.blockEntityNbt())
                : ExplosionAffectedList.parse(action.targetId())).isEmpty();
        }
        return false;
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
            case BLOCK_PLACE ->
                mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            case BLOCK_BREAK -> {
                if (nbt) {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta(),
                        a.oldBlockState(), a.blockEntityNbt());
                } else {
                    yield mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
                }
            }
            case CONTAINER_DEPOSIT ->
                mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case CONTAINER_WITHDRAW -> {
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case ITEM_DROP, ITEM_PICKUP -> {
                LOG.warn("RollbackEngine: refusing to roll back {} (id={}) — item entity identity required", a.type(), a.id());
                yield false;
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
                mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            // --- v0.1.0 expansion: containers ---
            case HOPPER_PUSH ->
                mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case HOPPER_PULL -> {
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
            case BLOCK_BREAK ->
                mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            case CONTAINER_DEPOSIT -> {
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case CONTAINER_WITHDRAW ->
                mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case ITEM_DROP, ITEM_PICKUP -> {
                LOG.warn("RollbackEngine: refusing to restore {} (id={}) — item entity identity required", a.type(), a.id());
                yield false;
            }
            case ENTITY_KILL -> {
                // Restoring a kill is intentionally a no-op (entity already dead path).
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
            case BURN, FADE, LEAVES_DECAY, BUCKET_FILL ->
                mutator.trySetBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
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
                if (nbt) {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta(), a.itemNbt());
                } else {
                    yield mutator.tryGiveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                        a.targetId(), Math.max(1, a.amount()), a.targetMeta());
                }
            }
            case HOPPER_PULL ->
                mutator.tryRemoveFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
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

    private boolean restoreExplosion(Action a) {
        boolean hasSidecar = a.blockEntityNbt() != null && a.blockEntityNbt().length > 0;
        ExplosionAffectedList list = hasSidecar
            ? ExplosionAffectedList.parse(a.targetId(), a.blockEntityNbt())
            : ExplosionAffectedList.parse(a.targetId());
        if (list.isEmpty()) return false;
        boolean allOk = true;
        for (ExplosionAffectedList.Entry e : list.entries()) {
            // Restore the pre-blast block state at each affected coord. Legacy
            // inline meta remains targetMeta; sidecar meta is v5 block-state props.
            boolean ok;
            if (hasSidecar && (e.meta() != null || e.blockEntityNbt() != null)) {
                ok = mutator.trySetBlock(a.worldId(), e.x(), e.y(), e.z(), e.blockId(), null,
                    e.meta(), e.blockEntityNbt());
            } else {
                ok = mutator.trySetBlock(a.worldId(), e.x(), e.y(), e.z(), e.blockId(), e.meta());
            }
            if (!ok) allOk = false;
        }
        return allOk;
    }

    private boolean clearExplosionBlocks(Action a) {
        ExplosionAffectedList list = ExplosionAffectedList.parse(a.targetId());
        if (list.isEmpty()) return false;
        boolean allOk = true;
        for (ExplosionAffectedList.Entry e : list.entries()) {
            // Restore direction: re-clear the affected area (re-apply the blast).
            if (!mutator.trySetBlock(a.worldId(), e.x(), e.y(), e.z(), AIR, null)) {
                allOk = false;
            }
        }
        return allOk;
    }

    /** For tests + internal use only. */
    static boolean isRollbackable(ActionType t) {
        return switch (t) {
            case BLOCK_PLACE, BLOCK_BREAK,
                 CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
                 ENTITY_KILL, EXPLOSION,
                 BURN, IGNITE, FADE, LEAVES_DECAY,
                 BUCKET_EMPTY, BUCKET_FILL, ENTITY_CHANGE_BLOCK,
                 HOPPER_PUSH, HOPPER_PULL,
                 HANGING_PLACE, HANGING_BREAK,
                 STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW,
                 INVENTORY_DEPOSIT, INVENTORY_WITHDRAW -> true;
            case CHAT, COMMAND, SIGN,
                 SESSION_JOIN, SESSION_LEAVE,
                 USERNAME_CHANGE,
                 DISPENSE, PISTON_EXTEND, PISTON_RETRACT,
                 ITEM_DROP, ITEM_PICKUP,
                 FORM, SPREAD,
                 ITEM_CRAFT,
                 ENTITY_SPAWN, ENTITY_INTERACT,
                 CHUNK_POPULATE, CLICK -> false;
        };
    }
}
