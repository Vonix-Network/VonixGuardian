package network.vonix.guardian.core.event;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, allocation-light classification of one player-inventory slot change.
 *
 * <p>Inventory mixins call this after a single slot mutation. Empty-to-item
 * and item-to-empty transitions are classified directly. Identity replacement
 * is represented as two ordered deltas: withdrawal of the old item followed by
 * deposit of the new item.</p>
 */
public record InventoryDelta(Kind kind, String itemId, int amount) {

    public static final InventoryDelta NONE = new InventoryDelta(Kind.NONE, null, 0);

    public InventoryDelta {
        Objects.requireNonNull(kind, "kind");
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        if (kind == Kind.NONE && (itemId != null || amount != 0)) {
            throw new IllegalArgumentException("NONE must not carry an item or amount");
        }
        if (kind != Kind.NONE && (itemId == null || itemId.isBlank() || amount == 0)) {
            throw new IllegalArgumentException("visible delta requires itemId and amount");
        }
    }

    public static InventoryDelta between(String beforeItemId, int beforeCount,
                                         String afterItemId, int afterCount) {
        int before = Math.max(0, beforeCount);
        int after = Math.max(0, afterCount);
        String beforeId = visibleId(beforeItemId, before);
        String afterId = visibleId(afterItemId, after);

        if (beforeId == null && afterId == null) return NONE;
        if (beforeId != null && afterId != null && !beforeId.equals(afterId)) return NONE;

        if (beforeId == null && afterId != null && after > 0) {
            return new InventoryDelta(Kind.DEPOSIT, afterId, after);
        }
        if (beforeId != null && afterId == null && before > 0) {
            return new InventoryDelta(Kind.WITHDRAW, beforeId, before);
        }
        if (beforeId == null || afterId == null || before != after) {
            if (after > before) return new InventoryDelta(Kind.DEPOSIT, afterId, after - before);
            if (before > after) return new InventoryDelta(Kind.WITHDRAW, beforeId, before - after);
        }
        return NONE;
    }

    public static List<InventoryDelta> betweenAll(String beforeItemId, int beforeCount,
                                                   String afterItemId, int afterCount) {
        return betweenAll(beforeItemId, beforeCount, afterItemId, afterCount, null, null);
    }

    /** Include item NBT identity when classifying same-id/count mutations. */
    public static List<InventoryDelta> betweenAll(String beforeItemId, int beforeCount,
                                                   String afterItemId, int afterCount,
                                                   byte[] beforeNbt, byte[] afterNbt) {
        int before = Math.max(0, beforeCount);
        int after = Math.max(0, afterCount);
        String beforeId = visibleId(beforeItemId, before);
        String afterId = visibleId(afterItemId, after);
        if (beforeId != null && afterId != null && beforeId.equals(afterId)
                && !Arrays.equals(beforeNbt, afterNbt)) {
            return List.of(
                    new InventoryDelta(Kind.WITHDRAW, beforeId, before),
                    new InventoryDelta(Kind.DEPOSIT, afterId, after));
        }
        if (beforeId != null && afterId != null && !beforeId.equals(afterId)) {
            return List.of(
                    new InventoryDelta(Kind.WITHDRAW, beforeId, before),
                    new InventoryDelta(Kind.DEPOSIT, afterId, after));
        }
        InventoryDelta single = between(beforeItemId, beforeCount, afterItemId, afterCount);
        return single.kind() == Kind.NONE ? List.of() : List.of(single);
    }

    private static String visibleId(String itemId, int count) {
        return count > 0 && itemId != null && !itemId.isBlank() ? itemId : null;
    }

    public enum Kind {
        NONE,
        DEPOSIT,
        WITHDRAW
    }
}
