package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered, deduplicated set of {@link Action}s to be undone or replayed, plus
 * the actions that were rejected because their {@link
 * network.vonix.guardian.core.action.ActionType} is not rollbackable.
 *
 * <p>The plan is built so that <strong>newer events are processed before older
 * events at the same {@code (world,x,y,z)} position</strong>, and each
 * positional slot is touched at most once — this prevents a later "place stone"
 * from overwriting the restoration of the "break diamond_ore" that happened
 * underneath it.</p>
 *
 * <p>Non-positional actions (chat / command / session / sign / username) are
 * always treated as not rollbackable and end up in {@link #skipped()}.</p>
 */
public final class RollbackPlan {

    private final List<Action> ordered;
    private final List<Action> skipped;

    private RollbackPlan(List<Action> ordered, List<Action> skipped) {
        this.ordered = Collections.unmodifiableList(ordered);
        this.skipped = Collections.unmodifiableList(skipped);
    }

    /** Empty plan (no actions, no skips). */
    public static RollbackPlan empty() {
        return new RollbackPlan(List.of(), List.of());
    }

    /**
     * Construct a plan from a raw DAO result set.
     *
     * @param actions raw rows from
     *                {@link network.vonix.guardian.core.storage.GuardianDao#query}
     * @return ordered, deduplicated plan
     */
    public static RollbackPlan build(List<Action> actions) {
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
        return new RollbackPlan(ordered, skipped);
    }

    /** Actions in the order they should be applied to the world. */
    public List<Action> ordered() {
        return ordered;
    }

    /** Actions excluded because their type is not rollbackable. */
    public List<Action> skipped() {
        return skipped;
    }

    /** Convenience: whether the plan contains zero applicable actions. */
    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /** Convenience: number of mutations the plan will dispatch. */
    public int size() {
        return ordered.size();
    }

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
