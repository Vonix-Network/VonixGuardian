package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.NbtPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slot-level container/hopper transport helpers: exact slot diffs, pair-ready
 * replacement detection, and bounded duplicate suppression.
 *
 * <p>Uses existing v8 {@code inventory_slot} / {@code pair_id} columns. No
 * schema migration.
 */
public final class ContainerTransport {

    /** CoreProtect-parity collapse window for identical consecutive captures. */
    public static final int DEDUPE_WINDOW_MS = 50;

    private static final int DEDUPE_CAP = 256;

    private static final ConcurrentHashMap<DedupeKey, Long> RECENT = new ConcurrentHashMap<>();

    private ContainerTransport() {}

    /** One container slot as observed by a producer snapshot. */
    public record SlotStack(int slot, String itemId, int count, byte[] comparisonNbt, byte[] fullNbt) {
        public SlotStack {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must be non-negative");
            }
            count = Math.max(0, count);
            comparisonNbt = NbtPayload.admit(comparisonNbt);
            fullNbt = NbtPayload.admit(fullNbt);
        }
    }

    /** One classified slot mutation. */
    public record SlotChange(int slot, String itemId, int amount, InventoryDelta.Kind kind, byte[] itemNbt) {
        public SlotChange {
            Objects.requireNonNull(kind, "kind");
            if (slot < 0) {
                throw new IllegalArgumentException("slot must be non-negative");
            }
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be non-negative");
            }
            itemNbt = NbtPayload.admit(itemNbt);
        }
    }

    /**
     * One side of a hopper transfer (source pull or destination push).
     *
     * @param x               container block x
     * @param y               container block y
     * @param z               container block z
     * @param itemId          item registry id
     * @param amount          stack count
     * @param itemNbt         full item payload, or {@code null}
     * @param slot            exact container slot
     * @param oldBlockState   pre-change block-state property string, or {@code null}
     * @param newBlockState   post-change block-state property string, or {@code null}
     * @param blockEntityNbt  block-entity snapshot, or {@code null}
     */
    public record TransferSide(int x, int y, int z, String itemId, int amount, byte[] itemNbt, Integer slot,
                               String oldBlockState, String newBlockState, byte[] blockEntityNbt) {
        public TransferSide {
            itemNbt = NbtPayload.admit(itemNbt);
            blockEntityNbt = NbtPayload.admit(blockEntityNbt);
        }
    }

    /** Diff two slot snapshots into ordered withdraw/deposit changes. */
    public static List<SlotChange> diff(List<SlotStack> before, List<SlotStack> after) {
        Map<Integer, SlotStack> beforeBySlot = index(before);
        Map<Integer, SlotStack> afterBySlot = index(after);
        TreeMap<Integer, Boolean> slots = new TreeMap<>();
        for (Integer k : beforeBySlot.keySet()) slots.put(k, Boolean.TRUE);
        for (Integer k : afterBySlot.keySet()) slots.put(k, Boolean.TRUE);
        List<SlotChange> out = new ArrayList<>();
        for (int slot : slots.keySet()) {
            SlotStack b = beforeBySlot.get(slot);
            SlotStack a = afterBySlot.get(slot);
            String beforeId = visibleId(b);
            String afterId = visibleId(a);
            int beforeCount = b == null ? 0 : b.count();
            int afterCount = a == null ? 0 : a.count();
            byte[] beforeNbt = b == null ? null : b.comparisonNbt();
            byte[] afterNbt = a == null ? null : a.comparisonNbt();
            List<InventoryDelta> deltas = InventoryDelta.betweenAll(
                    beforeId, beforeCount, afterId, afterCount, beforeNbt, afterNbt);
            for (InventoryDelta delta : deltas) {
                byte[] nbt = delta.kind() == InventoryDelta.Kind.DEPOSIT
                        ? (a == null ? null : a.fullNbt())
                        : (b == null ? null : b.fullNbt());
                out.add(new SlotChange(slot, delta.itemId(), delta.amount(), delta.kind(), nbt));
            }
        }
        return List.copyOf(out);
    }

    public static boolean isReplacement(List<SlotChange> changes) {
        return changes != null && changes.size() == 2
                && changes.get(0).kind() == InventoryDelta.Kind.WITHDRAW
                && changes.get(1).kind() == InventoryDelta.Kind.DEPOSIT
                && changes.get(0).slot() == changes.get(1).slot();
    }

    /**
     * Suppress an identical container/hopper capture seen within
     * {@link #DEDUPE_WINDOW_MS}. Actions without a slot are never suppressed.
     *
     * @return {@code true} when the caller must drop this capture
     */
    public static boolean suppressDuplicate(Action a, long nowMs) {
        if (a == null || a.inventorySlot() == null || a.targetId() == null) {
            return false;
        }
        DedupeKey key = new DedupeKey(
                a.worldId(), a.x(), a.y(), a.z(), a.inventorySlot(),
                a.targetId(), a.amount());
        Long prev = RECENT.get(key);
        if (prev != null && nowMs >= prev && nowMs - prev <= DEDUPE_WINDOW_MS) {
            return true;
        }
        RECENT.put(key, nowMs);
        if (RECENT.size() > DEDUPE_CAP) {
            evict(nowMs);
        }
        return false;
    }

    /** Test-only: drop in-memory duplicate state. */
    public static void resetDuplicatesForTest() {
        RECENT.clear();
    }

    private static Map<Integer, SlotStack> index(List<SlotStack> stacks) {
        Map<Integer, SlotStack> out = new TreeMap<>();
        if (stacks == null) {
            return out;
        }
        for (SlotStack stack : stacks) {
            if (stack != null) {
                out.put(stack.slot(), stack);
            }
        }
        return out;
    }

    private static String visibleId(SlotStack stack) {
        if (stack == null || stack.count() <= 0 || stack.itemId() == null || stack.itemId().isBlank()) {
            return null;
        }
        return stack.itemId();
    }

    private static void evict(long nowMs) {
        Iterator<Map.Entry<DedupeKey, Long>> it = RECENT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<DedupeKey, Long> e = it.next();
            Long ts = e.getValue();
            if (ts == null || nowMs - ts > DEDUPE_WINDOW_MS) {
                it.remove();
            }
        }
        while (RECENT.size() > DEDUPE_CAP) {
            DedupeKey first = RECENT.keySet().iterator().next();
            RECENT.remove(first);
        }
    }

    private record DedupeKey(String worldId, int x, int y, int z, int slot,
                             String itemId, int amount) {}
}
