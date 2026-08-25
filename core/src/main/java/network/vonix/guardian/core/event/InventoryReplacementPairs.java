package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correlation helper for player-inventory identity replacements.
 *
 * <p>A replacement is two durable rows — {@code INVENTORY_WITHDRAW} of the old
 * stack and {@code INVENTORY_DEPOSIT} of the new stack — that share one
 * {@code pair_id}. The token is distinct from fire/break pairing: only
 * inventory deposit/withdraw members are treated as a replacement pair.</p>
 */
public final class InventoryReplacementPairs {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final long NONCE = newNonce();

    private InventoryReplacementPairs() {}

    /** Process-local correlation id; never {@code 0}. */
    public static long nextPairId() {
        long seq = SEQ.incrementAndGet();
        long mixed = mix64(NONCE + seq);
        if (mixed != 0L) {
            return mixed;
        }
        mixed = mix64(NONCE ^ seq);
        return mixed != 0L ? mixed : seq;
    }

    public static boolean isMember(Action a) {
        return a != null && a.hasPairId()
                && (a.type() == ActionType.INVENTORY_WITHDRAW
                || a.type() == ActionType.INVENTORY_DEPOSIT);
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

    public static boolean isReplacement(java.util.List<InventoryDelta> deltas) {
        return deltas != null && deltas.size() == 2
                && deltas.get(0).kind() == InventoryDelta.Kind.WITHDRAW
                && deltas.get(1).kind() == InventoryDelta.Kind.DEPOSIT;
    }

    private static long newNonce() {
        long n;
        do {
            n = ThreadLocalRandom.current().nextLong();
        } while (n == 0L);
        return n;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
