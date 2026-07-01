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

    private final GuardianDao dao;
    private final WorldMutator mutator;
    private final Executor mainThreadExecutor;

    /**
     * @param dao                action store; must not be {@code null}
     * @param mutator            loader-supplied world mutator; must not be {@code null}
     * @param mainThreadExecutor executor that runs tasks on the server tick thread;
     *                           must not be {@code null}
     */
    public RollbackEngine(GuardianDao dao, WorldMutator mutator, Executor mainThreadExecutor) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.mutator = Objects.requireNonNull(mutator, "mutator");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public RollbackResult rollback(QueryFilter filter, boolean preview) throws Exception {
        return execute(plan(filter, RollbackResult.Mode.ROLLBACK, null), preview);
    }

    public RollbackResult restore(QueryFilter filter, boolean preview) throws Exception {
        return execute(plan(filter, RollbackResult.Mode.RESTORE, null), preview);
    }

    public RollbackResult rollback(QueryFilter filter, boolean preview, UUID actorUuid) throws Exception {
        return execute(plan(filter, RollbackResult.Mode.ROLLBACK, actorUuid), preview);
    }

    public RollbackResult restore(QueryFilter filter, boolean preview, UUID actorUuid) throws Exception {
        return execute(plan(filter, RollbackResult.Mode.RESTORE, actorUuid), preview);
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
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(mode, "mode");
        QueryFilter effective = withRolledBack(filter, mode == RollbackResult.Mode.RESTORE);
        List<Action> matches = fetchMatches(effective);
        if (matches.isEmpty()) {
            LOG.debug("RollbackEngine.{}: 0 matches", mode);
            return RollbackPlan.empty(effective, mode, actorUuid);
        }
        return RollbackPlan.build(matches, effective, mode, actorUuid);
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
     * Pages the DAO with the filter as given. The {@code rolledBack} predicate
     * MUST already be set on {@code filter} — this method no longer filters
     * client-side, which is the whole point of the v0.1.1 perf fix.
     */
    private List<Action> fetchMatches(QueryFilter filter) throws Exception {
        List<Action> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Action> page = dao.query(filter, offset, PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            out.addAll(page);
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        return out;
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
            base.optimize()
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
            // ENTITY_CHANGE_BLOCK: targetMeta carries oldBlockId (per EventSubmitter.submitEntityChangeBlock).
            case ENTITY_CHANGE_BLOCK ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetMeta() != null ? a.targetMeta() : AIR, null);
            // Block was destroyed/changed-away — inverse is to restore the original block.
            case BURN, IGNITE, FADE, LEAVES_DECAY, BUCKET_FILL ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            // Block was created — inverse is to clear it.
            case FORM, SPREAD, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            // --- v0.1.0 expansion: containers ---
            case HOPPER_PUSH ->
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case HOPPER_PULL ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            // --- v0.1.0 expansion: entities ---
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
            // TODO: add WorldMutator.removeHangingAt(worldId, x, y, z, entityType) so this can be honoured.
            case HANGING_PLACE ->
                LOG.warn("RollbackEngine: refusing to roll back HANGING_PLACE (id={}) — WorldMutator has no removeEntity API", a.id());
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
            // ENTITY_CHANGE_BLOCK: re-apply the newBlockId carried in targetId.
            case ENTITY_CHANGE_BLOCK ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), null);
            // Block was originally destroyed/changed-away — restoring means re-destroying.
            case BURN, IGNITE, FADE, LEAVES_DECAY, BUCKET_FILL ->
                mutator.setBlock(a.worldId(), a.x(), a.y(), a.z(), AIR, null);
            // Block was originally created — restoring means re-placing it.
            case FORM, SPREAD, BUCKET_EMPTY, STRUCTURE_GROW, PORTAL_CREATE ->
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
            // TODO: add WorldMutator.removeHangingAt(worldId, x, y, z, entityType) so this can be honoured.
            case HANGING_BREAK ->
                LOG.warn("RollbackEngine: refusing to restore HANGING_BREAK (id={}) — WorldMutator has no removeEntity API", a.id());
            case CHUNK_POPULATE ->
                LOG.warn("RollbackEngine: refusing to restore CHUNK_POPULATE (id={}) — chunk-scale revert unsafe", a.id());
            case CLICK ->
                LOG.warn("RollbackEngine: refusing to restore CLICK (id={}) — audit-only, no state change", a.id());
            case CHAT, COMMAND, SIGN, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE ->
                LOG.warn("RollbackEngine: refusing to restore non-rollbackable {} (id={})", a.type(), a.id());
        }
    }

    private void restoreExplosion(Action a) {
        String target = a.targetId();
        if (target == null || target.isEmpty()) {
            return;
        }
        for (String entry : target.split(",")) {
            ExplosionEntry e = parseExplosionEntry(entry);
            if (e == null) {
                continue;
            }
            mutator.setBlock(a.worldId(), e.x, e.y, e.z, e.id, e.meta);
        }
    }

    private void clearExplosionBlocks(Action a) {
        String target = a.targetId();
        if (target == null || target.isEmpty()) {
            return;
        }
        for (String entry : target.split(",")) {
            ExplosionEntry e = parseExplosionEntry(entry);
            if (e == null) {
                continue;
            }
            mutator.setBlock(a.worldId(), e.x, e.y, e.z, AIR, null);
        }
    }

    private static ExplosionEntry parseExplosionEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        try {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                return null;
            }
            String pos = entry.substring(0, eq);
            String rest = entry.substring(eq + 1);
            String id;
            String meta = null;
            int pipe = rest.indexOf('|');
            if (pipe >= 0) {
                id = rest.substring(0, pipe);
                meta = rest.substring(pipe + 1);
            } else {
                id = rest;
            }
            String[] xyz = pos.split(":");
            if (xyz.length != 3) {
                return null;
            }
            int x = Integer.parseInt(xyz[0].trim());
            int y = Integer.parseInt(xyz[1].trim());
            int z = Integer.parseInt(xyz[2].trim());
            return new ExplosionEntry(x, y, z, id.trim(), meta);
        } catch (NumberFormatException e) {
            LOG.warn("RollbackEngine: malformed explosion entry '{}'", entry);
            return null;
        }
    }

    /** For tests + internal use only. */
    static boolean isRollbackable(ActionType t) {
        return switch (t) {
            case BLOCK_PLACE, BLOCK_BREAK,
                 CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
                 ITEM_DROP, ITEM_PICKUP,
                 ENTITY_KILL, EXPLOSION -> true;
            case CHAT, COMMAND, SIGN,
                 SESSION_JOIN, SESSION_LEAVE,
                 USERNAME_CHANGE -> false;
            default -> false;
        };
    }

    private record ExplosionEntry(int x, int y, int z, String id, String meta) {}
}
