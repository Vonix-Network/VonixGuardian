package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;

/**
 * Correlation helper for hopper source/destination transfers.
 *
 * <p>A transfer is two durable rows — {@code HOPPER_PULL} from the source
 * container and {@code HOPPER_PUSH} into the destination — that share one
 * {@code pair_id}. Rollback of a lone member is fail-closed: a stale pair
 * must not mutate one side of the world.
 */
public final class HopperTransportPairs {

    private HopperTransportPairs() {}

    /** Process-local correlation id; never {@code 0}. */
    public static long nextPairId() {
        return InventoryReplacementPairs.nextPairId();
    }

    public static boolean isMember(Action a) {
        return a != null && a.hasPairId()
                && (a.type() == ActionType.HOPPER_PULL
                || a.type() == ActionType.HOPPER_PUSH);
    }

    public static boolean isPair(Action a, Action b) {
        if (!isMember(a) || !isMember(b) || a == b) {
            return false;
        }
        return a.pairId().equals(b.pairId()) && a.type() != b.type();
    }

    public static Action siblingOf(Action a, Iterable<Action> actions) {
        if (!isMember(a) || actions == null) {
            return null;
        }
        for (Action b : actions) {
            if (isPair(a, b)) {
                return b;
            }
        }
        return null;
    }
}
