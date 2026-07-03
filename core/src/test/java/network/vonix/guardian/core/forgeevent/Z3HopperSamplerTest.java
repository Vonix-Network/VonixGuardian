/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.forgeevent;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.3 Z3 — regression suite for the Forge hopper content-diff sampler.
 *
 * <p>The three Forge cells (1.18.2 / 1.19.2 / 1.20.1) implement a bounded
 * per-tick sampler that snapshots each tracked hopper's five item slots and
 * emits {@code submitHopperPush} (slot count increased) or
 * {@code submitHopperPull} (slot count decreased) when the snapshot differs
 * from the last observation. Because the sampler pulls Minecraft classes
 * ({@code HopperBlockEntity}, {@code ServerLevel}, {@code ItemStack}) we can't
 * unit-test the ForgeEvents.java code directly from {@code :core}; this test
 * mirrors the exact classifier pipeline in pure Java and asserts the
 * push/pull decision on every relevant transition.</p>
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li>First observation → no rows (bootstrap discipline)</li>
 *   <li>Slot count increased → {@code submitHopperPush}</li>
 *   <li>Slot count decreased → {@code submitHopperPull}</li>
 *   <li>Slot cleared → {@code submitHopperPull} with previous count</li>
 *   <li>Slot type swap (item A → item B) → {@code submitHopperPull(A)}
 *       + {@code submitHopperPush(B)}</li>
 *   <li>Zero net change (same slot content re-observed) → no rows</li>
 *   <li>All five slots simultaneously → five independent rows</li>
 * </ul>
 *
 * <p>Documented gap (see {@code ForgeEvents.java} § Z3 gap acknowledgement):
 * chained push+pull within the same sample window will cancel out and go
 * unrecorded, because the sampler only observes net delta between two
 * snapshots. This is the accepted trade-off for bounded per-tick work.</p>
 */
class Z3HopperSamplerTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void firstObservation_noRows() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:cobblestone|1", null, null, null, null});

        assertThat(cell.submitter.pushRows).isEmpty();
        assertThat(cell.submitter.pullRows).isEmpty();
    }

    @Test
    void slotCountIncreased_emitsPush() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:cobblestone|1", null, null, null, null});
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:cobblestone|4", null, null, null, null});

        assertThat(cell.submitter.pushRows).hasSize(1);
        Row r = cell.submitter.pushRows.get(0);
        assertThat(r.itemId).isEqualTo("minecraft:cobblestone");
        assertThat(r.amount).isEqualTo(3);
        assertThat(r.actorName).isEqualTo(Sentinel.HOPPER);
        assertThat(r.sourceTag).isEqualTo(Sentinel.HOPPER);
        assertThat(cell.submitter.pullRows).isEmpty();
    }

    @Test
    void slotCountDecreased_emitsPull() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:diamond|8", null, null, null, null});
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:diamond|3", null, null, null, null});

        assertThat(cell.submitter.pullRows).hasSize(1);
        Row r = cell.submitter.pullRows.get(0);
        assertThat(r.itemId).isEqualTo("minecraft:diamond");
        assertThat(r.amount).isEqualTo(5);
        assertThat(cell.submitter.pushRows).isEmpty();
    }

    @Test
    void slotCleared_emitsPullOfPreviousCount() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:iron_ingot|10", null, null, null, null});
        cell.observe(WORLD, 10, 64, 10, new String[]{null, null, null, null, null});

        assertThat(cell.submitter.pullRows).hasSize(1);
        Row r = cell.submitter.pullRows.get(0);
        assertThat(r.itemId).isEqualTo("minecraft:iron_ingot");
        assertThat(r.amount).isEqualTo(10);
        assertThat(cell.submitter.pushRows).isEmpty();
    }

    @Test
    void itemTypeSwap_emitsPullAndPush() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:cobblestone|32", null, null, null, null});
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:redstone|16", null, null, null, null});

        assertThat(cell.submitter.pullRows).hasSize(1);
        assertThat(cell.submitter.pullRows.get(0).itemId).isEqualTo("minecraft:cobblestone");
        assertThat(cell.submitter.pullRows.get(0).amount).isEqualTo(32);
        assertThat(cell.submitter.pushRows).hasSize(1);
        assertThat(cell.submitter.pushRows.get(0).itemId).isEqualTo("minecraft:redstone");
        assertThat(cell.submitter.pushRows.get(0).amount).isEqualTo(16);
    }

    @Test
    void noChange_noRows() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:cobblestone|1", null, null, null, null});
        cell.observe(WORLD, 10, 64, 10, new String[]{"minecraft:cobblestone|1", null, null, null, null});

        assertThat(cell.submitter.pushRows).isEmpty();
        assertThat(cell.submitter.pullRows).isEmpty();
    }

    @Test
    void allFiveSlotsChanged_emitsFiveRows() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, new String[]{
                "minecraft:cobblestone|1", "minecraft:dirt|1", "minecraft:sand|1",
                "minecraft:gravel|1", "minecraft:coal|1"
        });
        cell.observe(WORLD, 10, 64, 10, new String[]{
                "minecraft:cobblestone|2", "minecraft:dirt|2", "minecraft:sand|2",
                "minecraft:gravel|2", "minecraft:coal|2"
        });

        assertThat(cell.submitter.pushRows).hasSize(5);
        for (Row r : cell.submitter.pushRows) {
            assertThat(r.amount).isEqualTo(1);
        }
        assertThat(cell.submitter.pullRows).isEmpty();
    }

    @Test
    void independentHoppers_trackedSeparately() {
        Cell cell = new Cell();
        // Two hoppers, one at (0,64,0) one at (100,64,100)
        cell.observe(WORLD, 0, 64, 0, new String[]{"minecraft:cobblestone|1", null, null, null, null});
        cell.observe(WORLD, 100, 64, 100, new String[]{"minecraft:redstone|1", null, null, null, null});
        // Only the second one changes
        cell.observe(WORLD, 0, 64, 0, new String[]{"minecraft:cobblestone|1", null, null, null, null});
        cell.observe(WORLD, 100, 64, 100, new String[]{"minecraft:redstone|9", null, null, null, null});

        assertThat(cell.submitter.pushRows).hasSize(1);
        Row r = cell.submitter.pushRows.get(0);
        assertThat(r.itemId).isEqualTo("minecraft:redstone");
        assertThat(r.amount).isEqualTo(8);
        assertThat(r.x).isEqualTo(100);
        assertThat(r.z).isEqualTo(100);
    }

    @Test
    void sentinelDiscipline_actorAndTagAreHopper() {
        Cell cell = new Cell();
        cell.observe(WORLD, 5, 5, 5, new String[]{"minecraft:diamond|1", null, null, null, null});
        cell.observe(WORLD, 5, 5, 5, new String[]{"minecraft:diamond|2", null, null, null, null});

        Row r = cell.submitter.pushRows.get(0);
        assertThat(r.actorUuid).isNull();
        assertThat(r.actorName).isEqualTo(Sentinel.HOPPER);
        assertThat(r.sourceTag).isEqualTo(Sentinel.HOPPER);
    }

    /**
     * Mirrors the classifier logic in
     * {@code ForgeEvents#sampleHopperOne} inside the three Forge cells. Keeps a
     * per-position snapshot; on each observe() computes deltas and calls the
     * recorder's push/pull methods.
     */
    static final class Cell {
        static final int SLOTS = 5;
        final java.util.Map<Long, String[]> snapshots = new java.util.HashMap<>();
        final Recorder submitter = new Recorder();

        static long key(String worldId, int x, int y, int z) {
            long wh = worldId == null ? 0L : ((long) worldId.hashCode()) & 0xFFFFFFFFL;
            long xl = ((long) x) & 0x3FFFFFF;
            long yl = ((long) y) & 0xFFF;
            long zl = ((long) z) & 0x3FFFFFF;
            return (wh << 32) ^ (xl << 38) ^ (yl << 26) ^ zl;
        }

        void observe(String worldId, int x, int y, int z, String[] cur) {
            long k = key(worldId, x, y, z);
            String[] snap = snapshots.get(k);
            boolean firstObservation = snap == null || allNull(snap);
            if (snap == null) snap = new String[SLOTS];
            for (int i = 0; i < SLOTS; i++) {
                String curKey = cur[i];
                String prevKey = snap[i];
                snap[i] = curKey;
                if (firstObservation) continue;
                if (Objects.equals(prevKey, curKey)) continue;
                int prevCount = parseCount(prevKey);
                int curCount = parseCount(curKey);
                String prevId = parseId(prevKey);
                String curId = parseId(curKey);
                if (prevKey != null && curKey != null && curId != null && prevId != null
                        && !curId.equals(prevId)) {
                    submitter.submitHopperPull(null, Sentinel.HOPPER, worldId, x, y, z, prevId, prevCount, Sentinel.HOPPER);
                    submitter.submitHopperPush(null, Sentinel.HOPPER, worldId, x, y, z, curId, curCount, Sentinel.HOPPER);
                    continue;
                }
                String itemId = curKey != null ? curId : prevId;
                if (itemId == null) continue;
                int delta = curCount - prevCount;
                if (delta > 0) {
                    submitter.submitHopperPush(null, Sentinel.HOPPER, worldId, x, y, z, itemId, delta, Sentinel.HOPPER);
                } else if (delta < 0) {
                    submitter.submitHopperPull(null, Sentinel.HOPPER, worldId, x, y, z, itemId, -delta, Sentinel.HOPPER);
                }
            }
            snapshots.put(k, snap);
        }

        static boolean allNull(String[] a) {
            for (String s : a) if (s != null) return false;
            return true;
        }

        static String parseId(String key) {
            if (key == null) return null;
            int i = key.lastIndexOf('|');
            return i > 0 ? key.substring(0, i) : key;
        }

        static int parseCount(String key) {
            if (key == null) return 0;
            int i = key.lastIndexOf('|');
            if (i < 0 || i == key.length() - 1) return 0;
            try {
                return Integer.parseInt(key.substring(i + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    record Row(UUID actorUuid, String actorName, String worldId,
               int x, int y, int z, String itemId, int amount, String sourceTag) {}

    static final class Recorder implements EventSubmitter {
        final List<Row> pushRows = new ArrayList<>();
        final List<Row> pullRows = new ArrayList<>();

        @Override public void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {
            pushRows.add(new Row(u, n, w, x, y, z, i, a, s));
        }
        @Override public void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {
            pullRows.add(new Row(u, n, w, x, y, z, i, a, s));
        }

        // ---- unused ----
        @Override public void submit(Action a) {}
        @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitContainerChange(UUID u, String n, String w, int x, int y, int z, String i, int d, String s) {}
        @Override public void submitItemDrop(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemPickup(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntityKill(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntitySpawn(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntityInteract(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntityChangeBlock(UUID u, String n, String w, int x, int y, int z, String o, String nn, String s) {}
        @Override public void submitExplosion(UUID u, String n, String w, int x, int y, int z, String j, String s) {}
        @Override public void submitChat(UUID u, String n, String w, String m) {}
        @Override public void submitCommand(UUID u, String n, String w, String cmd) {}
        @Override public void submitSign(UUID u, String n, String w, int x, int y, int z, String j) {}
        @Override public void submitSessionJoin(UUID u, String n, String w, String i) {}
        @Override public void submitSessionLeave(UUID u, String n, String w, String r) {}
        @Override public void submitUsernameChange(UUID u, String nn, String w, String on) {}
        @Override public void submitDispense(UUID u, String n, String w, int x, int y, int z, String i, String s) {}
        @Override public void submitPistonExtend(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitPistonRetract(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketEmpty(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketFill(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitFluidFlow(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitInventoryDeposit(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitInventoryWithdraw(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
        @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}
        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
    }
}
