package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.query.QueryFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, ordered, deduplicated set of actions the {@link RollbackEngine}
 * will apply, plus enough metadata to re-execute or invert the plan later.
 *
 * <p>W2-01 refactor (v1.1.6): the plan is now a value object built by
 * {@link RollbackEngine#plan(QueryFilter, RollbackResult.Mode, UUID)}
 * <strong>before</strong> any executor dispatch. This lets callers inspect
 * (or discard) the plan without side effects and gives the {@code UndoStack}
 * a persistable snapshot of the originating filter (see A2).</p>
 *
 * <p>Ordering rule: <strong>newer events are processed before older events at
 * the same {@code (world,x,y,z)} position</strong>, and each positional slot
 * is touched at most once — this prevents a later "place stone" from
 * overwriting the restoration of the "break diamond_ore" that happened
 * underneath it.</p>
 *
 * <p>Non-positional actions (chat / command / session / sign / username) are
 * always treated as not rollbackable and end up in {@link #skippedIds()}.</p>
 */
public final class RollbackPlan {

    /**
     * A single scheduled mutation. Right now this is a thin wrapper around an
     * {@link Action}, but the wrapper lets us evolve dispatch metadata (batch
     * hints, per-step tags) without breaking the plan's public surface.
     */
    public record PlannedStep(Action action) {
        public PlannedStep {
            Objects.requireNonNull(action, "action");
        }
    }

    private final List<Action> ordered;
    private final List<Action> skipped;
    private final List<PlannedStep> steps;
    private final List<Long> actionIds;
    private final List<Long> skippedIds;
    private final QueryFilter originalFilter;
    private final RollbackResult.Mode mode;
    private final UUID actorUuid;

    private RollbackPlan(List<Action> ordered,
                         List<Action> skipped,
                         QueryFilter originalFilter,
                         RollbackResult.Mode mode,
                         UUID actorUuid) {
        this.ordered = Collections.unmodifiableList(ordered);
        this.skipped = Collections.unmodifiableList(skipped);
        List<PlannedStep> stepList = new ArrayList<>(ordered.size());
        List<Long> ids = new ArrayList<>(ordered.size());
        for (Action a : ordered) {
            stepList.add(new PlannedStep(a));
            ids.add(a.id());
        }
        List<Long> skIds = new ArrayList<>(skipped.size());
        for (Action a : skipped) skIds.add(a.id());
        this.steps = Collections.unmodifiableList(stepList);
        this.actionIds = Collections.unmodifiableList(ids);
        this.skippedIds = Collections.unmodifiableList(skIds);
        this.originalFilter = originalFilter;
        this.mode = mode;
        this.actorUuid = actorUuid;
    }

    /** Empty plan (no actions, no skips, no context). */
    public static RollbackPlan empty() {
        return new RollbackPlan(List.of(), List.of(), null, null, null);
    }

    /**
     * Empty plan tagged with the filter/mode/actor that produced it. Used by
     * {@link RollbackEngine} when a query returns zero rows so the resulting
     * {@link RollbackResult} still carries the originating filter for undo.
     */
    public static RollbackPlan empty(QueryFilter originalFilter,
                                     RollbackResult.Mode mode,
                                     UUID actorUuid) {
        return new RollbackPlan(List.of(), List.of(), originalFilter, mode, actorUuid);
    }

    /**
     * Construct a plan from a raw DAO result set. Preserved for backwards
     * compatibility with pre-W2-01 callers that don't carry filter context.
     */
    public static RollbackPlan build(List<Action> actions) {
        return build(actions, null, null, null);
    }

    /**
     * Construct a plan from a raw DAO result set, tagging it with the
     * filter/mode/actor context needed for undo replay.
     */
    public static RollbackPlan build(List<Action> actions,
                                     QueryFilter originalFilter,
                                     RollbackResult.Mode mode,
                                     UUID actorUuid) {
        Objects.requireNonNull(actions, "actions");
        List<Action> sorted = new ArrayList<>(actions);
        // newer first: descending timestamp, tiebreak descending id
        sorted.sort((a, b) -> {
            int c = Long.compare(b.timestamp(), a.timestamp());
            return c != 0 ? c : Long.compare(b.id(), a.id());
        });

        List<Action> ordered = new ArrayList<>(sorted.size());
        List<Action> skipped = new ArrayList<>();
        java.util.HashSet<PosKey> seen = new java.util.HashSet<>();

        for (Action a : sorted) {
            if (!isRollbackable(a)) {
                skipped.add(a);
                continue;
            }
            if (a.isPositional()) {
                PosKey k = new PosKey(a.worldId(), a.x(), a.y(), a.z());
                if (!seen.add(k)) {
                    // a later (newer) event already covers this slot — drop this older one
                    continue;
                }
            }
            ordered.add(a);
        }
        return new RollbackPlan(ordered, skipped, originalFilter, mode, actorUuid);
    }

    // -- new record-style accessors (W2-01) --

    /** IDs of actions this plan will touch, in dispatch order. */
    public List<Long> actionIds() { return actionIds; }

    /** IDs of actions excluded because their type is not rollbackable. */
    public List<Long> skippedIds() { return skippedIds; }

    /** Planned mutations, in dispatch order. */
    public List<PlannedStep> steps() { return steps; }

    /** The (rolledBack-normalized) filter the plan was built from; may be {@code null} for legacy callers. */
    public QueryFilter originalFilter() { return originalFilter; }

    /** {@link RollbackResult.Mode#ROLLBACK} or {@code RESTORE}; may be {@code null} for legacy callers. */
    public RollbackResult.Mode mode() { return mode; }

    /** Actor UUID; {@code null} for console / legacy. */
    public UUID actorUuid() { return actorUuid; }

    /** Number of mutations the plan will dispatch. */
    public int plannedSteps() { return steps.size(); }

    /** Whether the plan contains zero applicable actions. */
    public boolean isEmpty() { return steps.isEmpty(); }

    // -- legacy accessors kept for RollbackEngine + existing tests --

    /** Actions in the order they should be applied to the world. */
    public List<Action> ordered() { return ordered; }

    /** Actions excluded because their type is not rollbackable. */
    public List<Action> skipped() { return skipped; }

    /** Convenience: number of mutations the plan will dispatch. */
    public int size() { return ordered.size(); }

    static boolean isRollbackable(Action a) {
        return switch (a.type()) {
            case BLOCK_PLACE, BLOCK_BREAK,
                 CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
                 ITEM_DROP, ITEM_PICKUP,
                 ENTITY_KILL, EXPLOSION -> true;
            case CHAT, COMMAND, SIGN,
                 SESSION_JOIN, SESSION_LEAVE,
                 USERNAME_CHANGE -> false;
            // v0.1.0 expansion (15-39) lands in a follow-up wave; until the loader bridges
            // wire mutators for them, treat as not-rollbackable so the engine refuses cleanly.
            default -> false;
        };
    }

    private record PosKey(String worldId, int x, int y, int z) {}
}
