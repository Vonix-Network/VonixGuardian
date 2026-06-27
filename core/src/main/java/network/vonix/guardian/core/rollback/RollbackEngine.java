package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Coordinates rollback and restore of logged actions.
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Query the {@link GuardianDao} (caller thread — caller is a worker, not
 *       the server thread, per the DAO contract).</li>
 *   <li>Build a {@link RollbackPlan} that deduplicates by position and orders
 *       newest-first.</li>
 *   <li>If not a preview, submit the world mutations to the
 *       {@code mainThreadExecutor} in batches of {@link #BATCH_SIZE}.</li>
 *   <li>Mark the affected IDs in the DAO ({@code rolled_back=1} for rollback,
 *       {@code rolled_back=0} for restore).</li>
 * </ol>
 *
 * <p>The actual world mutations are delegated to a {@link WorldMutator}
 * supplied by the loader module. The engine is otherwise loader-agnostic.</p>
 */
public final class RollbackEngine {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackEngine.class);

    /** Max mutations dispatched per server tick. */
    public static final int BATCH_SIZE = 1000;

    /** Inert block id used to clear a {@code BLOCK_PLACE}. */
    static final String AIR = "minecraft:air";

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

    /**
     * Roll back actions matched by {@code filter} that have not already been
     * rolled back.
     *
     * @param filter  query filter; must not be {@code null}
     * @param preview when {@code true}, no mutations are dispatched and the DAO
     *                is not modified
     * @return result describing what was (or would be) done
     * @throws Exception on DAO failure
     */
    public RollbackResult rollback(QueryFilter filter, boolean preview) throws Exception {
        return execute(filter, preview, RollbackResult.Mode.ROLLBACK, null);
    }

    /**
     * Re-apply actions matched by {@code filter} that are currently in the
     * rolled-back state.
     *
     * @param filter  query filter; must not be {@code null}
     * @param preview when {@code true}, no mutations are dispatched and the DAO
     *                is not modified
     * @return result describing what was (or would be) done
     * @throws Exception on DAO failure
     */
    public RollbackResult restore(QueryFilter filter, boolean preview) throws Exception {
        return execute(filter, preview, RollbackResult.Mode.RESTORE, null);
    }

    /**
     * Variant of {@link #rollback} that tags the result with an originating
     * actor (used by callers that push onto an {@link UndoStack}).
     *
     * @param filter   query filter
     * @param preview  preview-only flag
     * @param actorUuid actor UUID; may be {@code null} for console
     * @return result
     * @throws Exception on DAO failure
     */
    public RollbackResult rollback(QueryFilter filter, boolean preview, UUID actorUuid) throws Exception {
        return execute(filter, preview, RollbackResult.Mode.ROLLBACK, actorUuid);
    }

    /**
     * Variant of {@link #restore} that tags the result with an originating actor.
     *
     * @param filter   query filter
     * @param preview  preview-only flag
     * @param actorUuid actor UUID; may be {@code null} for console
     * @return result
     * @throws Exception on DAO failure
     */
    public RollbackResult restore(QueryFilter filter, boolean preview, UUID actorUuid) throws Exception {
        return execute(filter, preview, RollbackResult.Mode.RESTORE, actorUuid);
    }

    // ---------------------------------------------------------------------

    private RollbackResult execute(QueryFilter filter,
                                   boolean preview,
                                   RollbackResult.Mode mode,
                                   UUID actorUuid) throws Exception {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(mode, "mode");

        List<Action> matches = fetchMatches(filter, mode);
        if (matches.isEmpty()) {
            LOG.debug("RollbackEngine.{}: 0 matches", mode);
            return new RollbackResult(actorUuid, mode, preview,
                List.of(), List.of(), 0, 0);
        }

        RollbackPlan plan = RollbackPlan.build(matches);
        List<Long> skippedIds = collectIds(plan.skipped());
        if (!plan.skipped().isEmpty()) {
            LOG.warn("RollbackEngine.{}: {} action(s) excluded as not rollbackable", mode, plan.skipped().size());
        }

        List<Long> affectedIds = collectIds(plan.ordered());
        int planned = plan.size();

        if (preview || planned == 0) {
            return new RollbackResult(actorUuid, mode, preview, affectedIds, skippedIds, planned, 0);
        }

        int dispatched = dispatchBatches(plan.ordered(), mode);

        // Mark IDs *after* dispatch is enqueued. Mutation completion is async on
        // the main-thread executor; the DB state reflects intent, not landing.
        boolean targetFlag = mode == RollbackResult.Mode.ROLLBACK;
        try {
            dao.markRolledBack(affectedIds, targetFlag);
        } catch (Exception e) {
            LOG.error("RollbackEngine.{}: failed to mark {} ids rolled_back={}",
                mode, affectedIds.size(), targetFlag, e);
            throw e;
        }

        return new RollbackResult(actorUuid, mode, false, affectedIds, skippedIds, planned, dispatched);
    }

    private List<Action> fetchMatches(QueryFilter filter, RollbackResult.Mode mode) throws Exception {
        // We page through the DAO; restoration only touches rolled_back=1 rows,
        // rollback only rolled_back=0 rows. The DAO does not filter by that
        // flag (per contract), so we filter client-side.
        final int pageSize = 5000;
        List<Action> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Action> page = dao.query(filter, offset, pageSize);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Action a : page) {
                if (mode == RollbackResult.Mode.ROLLBACK && !a.rolledBack()) {
                    out.add(a);
                } else if (mode == RollbackResult.Mode.RESTORE && a.rolledBack()) {
                    out.add(a);
                }
            }
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return out;
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
                // best-effort: clear the dropped stack from the ground
                mutator.removeFromContainer(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()));
            case ITEM_PICKUP ->
                mutator.giveOrDrop(a.worldId(), a.x(), a.y(), a.z(),
                    a.targetId(), Math.max(1, a.amount()), a.targetMeta());
            case ENTITY_KILL ->
                mutator.respawnEntity(a.worldId(), a.x(), a.y(), a.z(), a.targetId(), a.targetMeta());
            case EXPLOSION ->
                restoreExplosion(a);
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
                // restoring a kill = re-killing the respawn we just made: best-effort no-op
                LOG.debug("RollbackEngine: restore of ENTITY_KILL id={} is best-effort no-op", a.id());
            case EXPLOSION ->
                // Re-detonate by clearing all blocks the explosion originally removed.
                clearExplosionBlocks(a);
            case CHAT, COMMAND, SIGN, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE ->
                LOG.warn("RollbackEngine: refusing to restore non-rollbackable {} (id={})", a.type(), a.id());
        }
    }

    /**
     * EXPLOSION target field format (per shared contracts § 8):
     * comma-joined affected-block list, each entry {@code "x:y:z=id"} or
     * {@code "x:y:z=id|meta"} truncated at 4KB. Unknown / malformed entries are
     * skipped with a warning.
     */
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

    private static List<Long> collectIds(List<Action> actions) {
        if (actions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(actions.size());
        for (Action a : actions) {
            ids.add(a.id());
        }
        return Collections.unmodifiableList(ids);
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
        };
    }

    private record ExplosionEntry(int x, int y, int z, String id, String meta) {}
}
