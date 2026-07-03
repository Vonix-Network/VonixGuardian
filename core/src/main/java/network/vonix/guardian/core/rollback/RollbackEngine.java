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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

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
 *   <li>Mark the affected IDs in the DAO ({@code rolled_back=1} for rollback,
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
    /** Extra block radius applied to the supplemental EXPLOSION scan's spatial pre-filter. */
    private final int explosionSupplementalReach;

    /**
     * @param dao                action store; must not be {@code null}
     * @param mutator            loader-supplied world mutator; must not be {@code null}
     * @param mainThreadExecutor executor that runs tasks on the server tick thread;
     *                           must not be {@code null}
     */
    public RollbackEngine(GuardianDao dao, WorldMutator mutator, Executor mainThreadExecutor) {
        this(dao, mutator, mainThreadExecutor, MAX_TNT_REACH);
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
        this.dao = Objects.requireNonNull(dao, "dao");
        this.mutator = Objects.requireNonNull(mutator, "mutator");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        if (explosionSupplementalReach < 0) {
            throw new IllegalArgumentException(
                "explosionSupplementalReach must be >= 0 (got " + explosionSupplementalReach + ")");
        }
        this.explosionSupplementalReach = explosionSupplementalReach;
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
     * dispatches mutations to the main-thread executor, marks the affected
     * IDs in the DAO, then closes the batch row. In {@code preview} mode no
     * batch is opened and no mutations are dispatched.
     */
    public RollbackResult execute(RollbackPlan plan, boolean preview) throws Exception {
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
            return new RollbackResult(actorUuid, mode, preview,
                affectedIds, skippedIds, planned, 0, effective);
        }

        String filterJson = encodeFilter(effective);
        long batchId = dao.openRollbackBatch(actorUuid, mode.ordinal(), filterJson, affectedIds);

        int dispatched;
        try {
            dispatched = dispatchBatches(plan.ordered(), mode);
            // Mark IDs *after* dispatch is enqueued. Mutation completion is async on
            // the main-thread executor; the DB state reflects intent, not landing.
            boolean targetFlag = mode == RollbackResult.Mode.ROLLBACK;
            dao.markRolledBack(affectedIds, targetFlag);
        } catch (Exception e) {
            LOG.error("RollbackEngine.{}: batch id={} failed mid-flight; vg_rollback_batches row left OPEN for recovery",
                mode, batchId, e);
            throw e;
        }

        try {
            dao.closeRollbackBatch(batchId);
        } catch (Exception e) {
            LOG.error("RollbackEngine.{}: failed to close batch id={}", mode, batchId, e);
            throw e;
        }

        return new RollbackResult(actorUuid, mode, false,
            affectedIds, skippedIds, planned, dispatched, effective);
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

        while (true) {
            if (options.isCancelRequested()) {
                RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                options.publish(progress);
                throw new RollbackCancelledException(progress);
            }

            int remainingScanBudget = options.maxScannedActions() - scanned;
            if (remainingScanBudget <= 0) {
                if (hasMoreRows(filter, offset)) {
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
            List<Action> pageWithProbe = dao.query(filter, offset, limit + 1);
            if (pageWithProbe == null || pageWithProbe.isEmpty()) {
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
            offset += page.size();
        }

        // W5 (v1.3.0): supplemental EXPLOSION scan — the primary DAO query filters
        // by center coord against the caller's radius, so it misses TNT whose center
        // is outside the box but whose affected-list reaches into it. Match
        // CoreProtect: loop through the affected-list at rollback time and admit
        // the row if ANY block in it falls within the caller's radius.
        supplementExplosions(filter, builder, options, pages, scanned);
        return builder.build();
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
        while (true) {
            if (options.isCancelRequested()) {
                RollbackProgress progress = progress(pages, scanned, builder, false, false, true);
                options.publish(progress);
                throw new RollbackCancelledException(progress);
            }
            int remainingScanBudget = options.maxScannedActions() - scanned;
            if (remainingScanBudget <= 0) {
                if (hasMoreRows(supp, offset)) {
                    RollbackProgress progress = progress(pages, scanned, builder, true, false, false);
                    options.publish(progress);
                    throw new RollbackLimitExceededException(
                        "Rollback planning exceeded scan cap of " + options.maxScannedActions() + " action(s)",
                        progress);
                }
                break;
            }
            int limit = Math.min(options.pageSize(), remainingScanBudget);
            List<Action> page = dao.query(supp, offset, limit);
            if (page == null || page.isEmpty()) break;
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
            null                  // worldEditPlayer cleared — WE region already covers primary
        );
    }

    private boolean hasMoreRows(QueryFilter filter, int offset) throws Exception {
        List<Action> probe = dao.query(filter, offset, 1);
        return probe != null && !probe.isEmpty();
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
            base.worldEditPlayer()
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

    private int dispatchBatches(List<Action> ordered, RollbackResult.Mode mode) {
        int total = 0;
        for (int i = 0; i < ordered.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, ordered.size());
            List<Action> batch = List.copyOf(ordered.subList(i, end));
            mainThreadExecutor.execute(() -> applyBatch(batch, mode));
            total += batch.size();
        }
        return total;
    }

    private void applyBatch(List<Action> batch, RollbackResult.Mode mode) {
        for (Action a : batch) {
            try {
                if (mode == RollbackResult.Mode.ROLLBACK) {
                    applyInverse(a);
                } else {
                    applyForward(a);
                }
            } catch (RuntimeException e) {
                LOG.warn("RollbackEngine: mutation failed for action id={} type={} ({})",
                    a.id(), a.type(), e.toString());
            }
        }
    }

    /** Apply the inverse of the action (used by rollback). */
    private void applyInverse(Action a) {
        switch (a.type()) {
            case BLOCK_PLACE ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            case BLOCK_BREAK ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            case CONTAINER_DEPOSIT ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case CONTAINER_WITHDRAW ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            case ITEM_DROP ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case ITEM_PICKUP ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            case ENTITY_KILL ->
                mutator.respawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            case EXPLOSION ->
                restoreExplosion(a);
            // --- v0.1.0 expansion: block events ---
            // ENTITY_CHANGE_BLOCK: targetId carries oldBlockId; targetMeta carries newBlockId.
            case ENTITY_CHANGE_BLOCK ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), null);
            // Block was destroyed/changed-away — inverse is to restore the original block.
            case BURN, IGNITE, FADE, LEAVES_DECAY, BUCKET_FILL ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            // Block was created — inverse is to clear it.
            case FORM, SPREAD, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            // --- v0.1.0 expansion: containers ---
            case HOPPER_PUSH ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case HOPPER_PULL ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            // --- v0.1.0 expansion: entities ---
            case HANGING_PLACE ->
                mutator.removeEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId());
            case HANGING_BREAK ->
                mutator.respawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            // --- per-action explicit refusals (replacing the silent default branch) ---
            case DISPENSE ->
                LOG.warn("RollbackEngine: refusing to roll back DISPENSE (id={}) — container slot tracking required", a.id());
            case PISTON_EXTEND, PISTON_RETRACT ->
                LOG.warn("RollbackEngine: refusing to roll back {} (id={}) — source position not tracked", a.type(), a.id());
            case INVENTORY_DEPOSIT, INVENTORY_WITHDRAW ->
                LOG.warn("RollbackEngine: refusing to roll back {} (id={}) — player inventory mutation out of scope", a.type(), a.id());
            case ITEM_CRAFT ->
                LOG.warn("RollbackEngine: refusing to roll back ITEM_CRAFT (id={}) — inventory state required", a.id());
            case ENTITY_SPAWN ->
                LOG.warn("RollbackEngine: refusing to roll back ENTITY_SPAWN (id={}) — despawn unsafe", a.id());
            case ENTITY_INTERACT ->
                LOG.warn("RollbackEngine: refusing to roll back ENTITY_INTERACT (id={}) — no state change to undo", a.id());
            case CHUNK_POPULATE ->
                LOG.warn("RollbackEngine: refusing to roll back CHUNK_POPULATE (id={}) — chunk-scale revert unsafe", a.id());
            case CLICK ->
                LOG.warn("RollbackEngine: refusing to roll back CLICK (id={}) — audit-only, no state change", a.id());
            case CHAT, COMMAND, SIGN, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE ->
                LOG.warn("RollbackEngine: refusing to roll back non-rollbackable {} (id={})", a.type(), a.id());
        }
    }

    /** Reapply the original action (used by restore). */
    private void applyForward(Action a) {
        switch (a.type()) {
            case BLOCK_PLACE ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            case BLOCK_BREAK ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            case CONTAINER_DEPOSIT ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            case CONTAINER_WITHDRAW ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case ITEM_DROP ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            case ITEM_PICKUP ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case ENTITY_KILL ->
                LOG.debug("RollbackEngine: restore of ENTITY_KILL id={} is best-effort no-op", a.id());
            case EXPLOSION ->
                clearExplosionBlocks(a);
            // --- v0.1.0 expansion: block events ---
            // ENTITY_CHANGE_BLOCK: re-apply the newBlockId carried in targetMeta.
            case ENTITY_CHANGE_BLOCK ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetMeta() != null ? a.targetMeta() : AIR, null);
            // Block was originally destroyed/changed-away — restoring means re-destroying.
            case BURN, IGNITE, FADE, LEAVES_DECAY, BUCKET_FILL ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            // Block was originally created — restoring means re-placing it.
            case FORM, SPREAD, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            // --- v0.1.0 expansion: containers ---
            case HOPPER_PUSH ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            case HOPPER_PULL ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            // --- v0.1.0 expansion: entities ---
            case HANGING_PLACE ->
                mutator.respawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            case HANGING_BREAK ->
                mutator.removeEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId());
            // --- per-action explicit refusals (replacing the silent default branch) ---
            case DISPENSE ->
                LOG.warn("RollbackEngine: refusing to restore DISPENSE (id={}) — container slot tracking required", a.id());
            case PISTON_EXTEND, PISTON_RETRACT ->
                LOG.warn("RollbackEngine: refusing to restore {} (id={}) — source position not tracked", a.type(), a.id());
            case INVENTORY_DEPOSIT, INVENTORY_WITHDRAW ->
                LOG.warn("RollbackEngine: refusing to restore {} (id={}) — player inventory mutation out of scope", a.type(), a.id());
            case ITEM_CRAFT ->
                LOG.warn("RollbackEngine: refusing to restore ITEM_CRAFT (id={}) — inventory state required", a.id());
            case ENTITY_SPAWN ->
                LOG.warn("RollbackEngine: refusing to restore ENTITY_SPAWN (id={}) — despawn unsafe", a.id());
            case ENTITY_INTERACT ->
                LOG.warn("RollbackEngine: refusing to restore ENTITY_INTERACT (id={}) — no state change to redo", a.id());
            case CHUNK_POPULATE ->
                LOG.warn("RollbackEngine: refusing to restore CHUNK_POPULATE (id={}) — chunk-scale revert unsafe", a.id());
            case CLICK ->
                LOG.warn("RollbackEngine: refusing to restore CLICK (id={}) — audit-only, no state change", a.id());
            case CHAT, COMMAND, SIGN, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE ->
                LOG.warn("RollbackEngine: refusing to restore non-rollbackable {} (id={})", a.type(), a.id());
        }
    }

    private void restoreExplosion(Action a) {
        ExplosionAffectedList list = ExplosionAffectedList.parse(a.targetId());
        if (list.isEmpty()) return;
        for (ExplosionAffectedList.Entry e : list.entries()) {
            // Restore the pre-blast block state at each affected coord.
            mutator.setBlock(a.worldId(), e.x(), e.y(), e.z(), e.blockId(), e.meta());
        }
    }

    private void clearExplosionBlocks(Action a) {
        ExplosionAffectedList list = ExplosionAffectedList.parse(a.targetId());
        if (list.isEmpty()) return;
        for (ExplosionAffectedList.Entry e : list.entries()) {
            // Restore direction: re-clear the affected area (re-apply the blast).
            mutator.setBlock(a.worldId(), e.x(), e.y(), e.z(), AIR, null);
        }
    }

    /** For tests + internal use only. */
    static boolean isRollbackable(ActionType t) {
        return switch (t) {
            case BLOCK_PLACE, BLOCK_BREAK,
                 CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
                 ITEM_DROP, ITEM_PICKUP,
                 ENTITY_KILL, EXPLOSION,
                 BURN, IGNITE, FADE, FORM, SPREAD, LEAVES_DECAY,
                 BUCKET_EMPTY, BUCKET_FILL, ENTITY_CHANGE_BLOCK,
                 HOPPER_PUSH, HOPPER_PULL,
                 HANGING_PLACE, HANGING_BREAK,
                 STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW -> true;
            case CHAT, COMMAND, SIGN,
                 SESSION_JOIN, SESSION_LEAVE,
                 USERNAME_CHANGE,
                 DISPENSE, PISTON_EXTEND, PISTON_RETRACT,
                 INVENTORY_DEPOSIT, INVENTORY_WITHDRAW, ITEM_CRAFT,
                 ENTITY_SPAWN, ENTITY_INTERACT,
                 CHUNK_POPULATE, CLICK -> false;
        };
    }
}
