package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>Every row is accounted for.</b> Planning partitions the DAO result set
 * into exactly two buckets: {@link #steps()} and {@link #skipped()}. Every skip
 * carries a {@link RollbackResult.SkipReason} recorded at the moment the
 * decision was made, so {@code scanned == planned + skipped} always holds and
 * the operator can reconcile the numbers. Nothing that is knowable from the row
 * itself is deferred to dispatch.</p>
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

    /**
     * A row the plan will not apply, with the reason recorded at decision time.
     * Storing the reason (rather than re-deriving it from the action) is what
     * keeps {@code superseded-by-newer-edit} distinct from
     * {@code type-not-reversible}.
     */
    public record SkippedAction(Action action, RollbackResult.SkipReason reason) {
        public SkippedAction {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final List<Action> ordered;
    private final List<SkippedAction> skippedEntries;
    private final List<Action> skipped;
    private final List<PlannedStep> steps;
    private final List<Long> actionIds;
    private final List<Long> skippedIds;
    private final Map<RollbackResult.SkipReason, Integer> skippedByReason;
    private final Map<RollbackResult.SkipReason, List<Long>> skippedIdsByReason;
    private final List<Action> invalid;
    private final QueryFilter originalFilter;
    private final RollbackResult.Mode mode;
    private final UUID actorUuid;

    private RollbackPlan(List<Action> ordered,
                         List<SkippedAction> skippedEntries,
                         QueryFilter originalFilter,
                         RollbackResult.Mode mode,
                         UUID actorUuid) {
        this.ordered = Collections.unmodifiableList(ordered);
        this.skippedEntries = Collections.unmodifiableList(skippedEntries);
        List<PlannedStep> stepList = new ArrayList<>(ordered.size());
        List<Long> ids = new ArrayList<>(ordered.size());
        for (Action a : ordered) {
            stepList.add(new PlannedStep(a));
            ids.add(a.id());
        }
        List<Action> skippedList = new ArrayList<>(skippedEntries.size());
        List<Long> skIds = new ArrayList<>(skippedEntries.size());
        List<Action> invalidList = new ArrayList<>();
        Map<RollbackResult.SkipReason, Integer> counts =
            new EnumMap<>(RollbackResult.SkipReason.class);
        Map<RollbackResult.SkipReason, List<Long>> idsByReason =
            new EnumMap<>(RollbackResult.SkipReason.class);
        for (SkippedAction s : skippedEntries) {
            skippedList.add(s.action());
            skIds.add(s.action().id());
            counts.merge(s.reason(), 1, Integer::sum);
            idsByReason.computeIfAbsent(s.reason(), k -> new ArrayList<>()).add(s.action().id());
            if (s.reason() == RollbackResult.SkipReason.MALFORMED_PAYLOAD) {
                invalidList.add(s.action());
            }
        }
        this.steps = Collections.unmodifiableList(stepList);
        this.actionIds = Collections.unmodifiableList(ids);
        this.skipped = Collections.unmodifiableList(skippedList);
        this.skippedIds = Collections.unmodifiableList(skIds);
        this.invalid = Collections.unmodifiableList(invalidList);
        this.skippedByReason = counts.isEmpty()
            ? Map.of() : Collections.unmodifiableMap(counts);
        if (idsByReason.isEmpty()) {
            this.skippedIdsByReason = Map.of();
        } else {
            Map<RollbackResult.SkipReason, List<Long>> frozen =
                new EnumMap<>(RollbackResult.SkipReason.class);
            for (Map.Entry<RollbackResult.SkipReason, List<Long>> e : idsByReason.entrySet()) {
                frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
            }
            this.skippedIdsByReason = Collections.unmodifiableMap(frozen);
        }
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
        List<SkippedAction> skipped = new ArrayList<>();
        HashSet<PosKey> seen = new HashSet<>();

        for (Action a : sorted) {
            // Plan-time validation: anything knowable from the row alone is
            // resolved NOW and counted as a named skip, so execution can never
            // discover it mid-run.
            RollbackResult.SkipReason rejection = rejectionReason(a, mode);
            if (rejection != null) {
                skipped.add(new SkippedAction(a, rejection));
                continue;
            }
            if (dedupeByPosition(a)) {
                PosKey k = new PosKey(a.worldId(), a.x(), a.y(), a.z());
                if (!seen.add(k)) {
                    // A newer row already restores this block. The older row is
                    // not applied, and it must be counted — dropping it made
                    // skipped=0 while rows the operator asked about vanished.
                    skipped.add(new SkippedAction(a, RollbackResult.SkipReason.SUPERSEDED_POSITION));
                    continue;
                }
            }
            ordered.add(a);
        }
        orderForExecution(ordered, mode);
        return new RollbackPlan(ordered, skipped, originalFilter, mode, actorUuid);
    }

    /**
     * Start an incremental plan builder for large rollback/restore operations.
     *
     * <p>The DAO emits rows in canonical newest-first order
     * ({@code ts DESC, id DESC}). This builder consumes one page at a time,
     * only retaining the bounded final plan/skipped lists plus the positional
     * de-dupe set; it never needs the full raw DAO match set in memory.</p>
     */
    static StreamingBuilder streaming(QueryFilter originalFilter,
                                      RollbackResult.Mode mode,
                                      UUID actorUuid) {
        return new StreamingBuilder(originalFilter, mode, actorUuid);
    }

    static final class StreamingBuilder {
        private final QueryFilter originalFilter;
        private final RollbackResult.Mode mode;
        private final UUID actorUuid;
        private final List<Action> ordered = new ArrayList<>();
        private final List<SkippedAction> skipped = new ArrayList<>();
        private final HashSet<PosKey> seen = new HashSet<>();
        private final HashSet<Long> seenIds = new HashSet<>();
        private final HashSet<Long> pairIds = new HashSet<>();

        private StreamingBuilder(QueryFilter originalFilter,
                                 RollbackResult.Mode mode,
                                 UUID actorUuid) {
            this.originalFilter = originalFilter;
            this.mode = mode;
            this.actorUuid = actorUuid;
        }

        void add(Action a) {
            Objects.requireNonNull(a, "action");
            if (a.id() >= 0L && !seenIds.add(a.id())) {
                return;
            }
            if (a.hasPairId()) {
                pairIds.add(a.pairId());
            }
            RollbackResult.SkipReason rejection = rejectionReason(a, mode);
            if (rejection != null) {
                skipped.add(new SkippedAction(a, rejection));
                return;
            }
            if (dedupeByPosition(a)) {
                PosKey k = new PosKey(a.worldId(), a.x(), a.y(), a.z());
                if (!seen.add(k)) {
                    skipped.add(new SkippedAction(a, RollbackResult.SkipReason.SUPERSEDED_POSITION));
                    return;
                }
            }
            ordered.add(a);
        }

        int plannedSteps() { return ordered.size(); }
        int skippedActions() { return skipped.size(); }
        java.util.Set<Long> pairIds() { return pairIds; }

        RollbackPlan build() {
            if (ordered.isEmpty() && skipped.isEmpty()) {
                return RollbackPlan.empty(originalFilter, mode, actorUuid);
            }
            orderForExecution(ordered, mode);
            return new RollbackPlan(new ArrayList<>(ordered), new ArrayList<>(skipped),
                originalFilter, mode, actorUuid);
        }
    }

    // -- new record-style accessors (W2-01) --

    /** IDs of actions this plan will touch, in dispatch order. */
    public List<Long> actionIds() { return actionIds; }

    /** IDs of actions excluded at plan time, in the order they were excluded. */
    public List<Long> skippedIds() { return skippedIds; }

    /** Planned mutations, in dispatch order. */
    public List<PlannedStep> steps() { return steps; }

    /**
     * Skips split by reason. Totals always reconcile with
     * {@link #size()} + {@link #skipped()}.
     */
    public Map<RollbackResult.SkipReason, Integer> skippedByReason() { return skippedByReason; }

    /** Skipped action ids grouped by reason; the union is {@link #skippedIds()}. */
    public Map<RollbackResult.SkipReason, List<Long>> skippedIdsByReason() { return skippedIdsByReason; }

    /** Count of rows skipped for one reason; {@code 0} when none. */
    public int skippedCount(RollbackResult.SkipReason reason) {
        Integer n = reason == null ? null : skippedByReason.get(reason);
        return n == null ? 0 : n;
    }

    /** Skipped rows with their recorded reasons. */
    public List<SkippedAction> skippedEntries() { return skippedEntries; }

    /**
     * Rows whose stored payload failed plan-time validation (currently an
     * unreadable EXPLOSION affected-list). They are also present in
     * {@link #skipped()} — this accessor exists so callers can name the defect
     * without re-deriving it. Executing a plan never discovers these mid-run.
     */
    public List<Action> invalid() { return invalid; }

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

    /** Actions excluded from the plan, with their reasons available via {@link #skippedEntries()}. */
    public List<Action> skipped() { return skipped; }

    /** Convenience: number of mutations the plan will dispatch. */
    public int size() { return ordered.size(); }

    private static void orderForExecution(List<Action> actions, RollbackResult.Mode mode) {
        if (mode != RollbackResult.Mode.RESTORE) return;

        // Keep non-inventory world operations in their original newest-first
        // positions, but rewrite the inventory positions from oldest to newest.
        // This handles replacement pairs separated by unrelated world rows.
        List<Action> inventory = new ArrayList<>();
        for (Action action : actions) {
            if (isInventoryAction(action)) inventory.add(action);
        }
        inventory.sort((a, b) -> {
            int c = Long.compare(a.timestamp(), b.timestamp());
            return c != 0 ? c : Long.compare(a.id(), b.id());
        });
        int next = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (isInventoryAction(actions.get(i))) actions.set(i, inventory.get(next++));
        }
    }

    private static boolean isInventoryAction(Action a) {
        return a.type() == ActionType.INVENTORY_DEPOSIT
            || a.type() == ActionType.INVENTORY_WITHDRAW;
    }

    static boolean isRollbackable(Action a) {
        return a != null && a.type() != null && RollbackEngine.isRollbackable(a.type());
    }

    /**
     * Why this row cannot be planned, or {@code null} when it can.
     *
     * <p>This is the single rule for "a rollback must not fail at dispatch".
     * Everything decidable from the row alone — unknown type, missing actor or
     * item payload, unparsable explosion payload — is decided here, at plan
     * time, and reported as a named skip. Nothing below is deferred to
     * execution, so a dispatch-time refusal would be a programming error rather
     * than a runtime possibility.</p>
     *
     * @param a    row from the DAO
     * @param mode direction the caller asked for; {@code null} for legacy plans
     *             built without one
     * @return the reason the row is excluded, or {@code null} to plan it
     */
    static RollbackResult.SkipReason rejectionReason(Action a, RollbackResult.Mode mode) {
        if (a == null || a.type() == null) {
            return RollbackResult.SkipReason.MALFORMED_PAYLOAD;
        }
        ActionType type = a.type();

        if (!RollbackEngine.isRollbackable(type)) {
            if (type == ActionType.CLICK) {
                // A click is recorded evidence of an interaction, not a state
                // change. The block it affected is its own row. Saying so beats
                // folding it into the generic audit-only bucket with chat.
                return RollbackResult.SkipReason.INTERACTION_ONLY;
            }
            return RollbackEngine.isAuditOnly(type)
                ? RollbackResult.SkipReason.AUDIT_ONLY
                : RollbackResult.SkipReason.UNTRACKED_STATE;
        }
        // Restoring a kill would mean re-killing an entity that is already dead.
        // It was previously skipped mid-batch, which made the operator's
        // skipped= count appear out of nowhere; decide it at plan time instead.
        if (type == ActionType.ENTITY_KILL && mode == RollbackResult.Mode.RESTORE) {
            return RollbackResult.SkipReason.UNSUPPORTED_ENTITY;
        }
        // Rolling back a kill needs an entity id the loader can respawn.
        if (type == ActionType.ENTITY_KILL && mode == RollbackResult.Mode.ROLLBACK
                && a.targetId() == null) {
            return RollbackResult.SkipReason.MALFORMED_PAYLOAD;
        }
        if (type == ActionType.INVENTORY_DEPOSIT || type == ActionType.INVENTORY_WITHDRAW) {
            if (a.actorUuid() == null) {
                // The actor UUID is the integrity boundary: without it the
                // mutation cannot be aimed at anyone, so it was never a valid
                // request. Refusing it mid-batch made the operator count wrong.
                return RollbackResult.SkipReason.MISSING_ACTOR;
            }
            if (a.itemNbt() == null) {
                // Item identity must be byte-exact or the wrong stack is moved. This is
                // almost always storage.persistNbt=false at capture time, not damage -
                // naming it separately turns an opaque count into an actionable one.
                return RollbackResult.SkipReason.MISSING_ITEM_IDENTITY;
            }
        }
        if (type == ActionType.EXPLOSION) {
            // The affected-list IS the payload. If it will not parse there is no
            // mutation to attempt, and learning that from the middle of a
            // running batch is exactly the defect being closed.
            boolean hasSidecar = a.blockEntityNbt() != null && a.blockEntityNbt().length > 0;
            ExplosionAffectedList list = hasSidecar
                ? ExplosionAffectedList.parse(a.targetId(), a.blockEntityNbt())
                : ExplosionAffectedList.parse(a.targetId());
            if (list.isEmpty()) {
                return RollbackResult.SkipReason.MALFORMED_PAYLOAD;
            }
        }
        return null;
    }

    /**
     * Coordinate de-dupe is only safe for block-state mutations where one
     * newer row makes older rows at the same block unreachable. Container,
     * item/entity, and chunked EXPLOSION rows may legitimately share a
     * coordinate and must all survive planning.
     */
    private static boolean dedupeByPosition(Action a) {
        if (a == null || !a.isPositional()) return false;
        return switch (a.type()) {
            case BLOCK_PLACE, BLOCK_BREAK,
                 BURN, IGNITE, FADE, LEAVES_DECAY,
                 BUCKET_EMPTY, BUCKET_FILL, ENTITY_CHANGE_BLOCK,
                 STRUCTURE_GROW, PORTAL_CREATE, FLUID_FLOW -> true;
            default -> false;
        };
    }

    private record PosKey(String worldId, int x, int y, int z) {}
}
