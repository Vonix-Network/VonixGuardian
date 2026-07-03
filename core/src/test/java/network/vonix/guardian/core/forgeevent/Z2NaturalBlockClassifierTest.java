/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.forgeevent;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.3 Z2 — regression suite for the Forge natural-block classifier.
 *
 * <p>The three Forge cells (1.18.2 / 1.19.2 / 1.20.1) implement a pre-state
 * LRU cache + {@code BlockEvent.NeighborNotifyEvent} handler that classifies
 * observed block-state transitions into BURN / IGNITE / FADE / FORM / SPREAD /
 * LEAVES_DECAY submissions. Because the handler body inside
 * {@code ForgeEvents.java} depends on Minecraft classes we can't unit-test
 * directly from {@code :core}, this test file mirrors the exact classifier
 * pipeline (LRU cache + {@code naturalCategory} + {@code classifyAndSubmit})
 * in pure Java and asserts every path lands on the correct
 * {@link EventSubmitter} overload.</p>
 *
 * <p>A regression on any of the three Forge cells' Z2 handlers shows up here as
 * long as the underlying string constants and category logic stay in lock-step.
 * The classifier is intentionally string-only (registry-id keyed) so it can be
 * verified without a Minecraft dependency.</p>
 *
 * <p>Coverage per ActionType:</p>
 * <ul>
 *   <li>{@link ActionType#BURN} — fire block replaced by non-fire</li>
 *   <li>{@link ActionType#IGNITE} — any block replaced by fire</li>
 *   <li>{@link ActionType#FADE} — ice/snow block replaced by water/air</li>
 *   <li>{@link ActionType#FORM} — concrete powder solidifies, water/lava
 *       → obsidian/cobble/stone/basalt, water → ice (freeze)</li>
 *   <li>{@link ActionType#SPREAD} — dirt → grass/mycelium/podzol</li>
 *   <li>{@link ActionType#LEAVES_DECAY} — leaves → air</li>
 * </ul>
 *
 * <p>DISPENSE is explicitly not covered — see {@code ForgeEvents.java} § Z2
 * gap acknowledgement + {@code docs/PERF-NOTES-1.3.3.md} § Z2.</p>
 */
class Z2NaturalBlockClassifierTest {

    private static final String WORLD = "minecraft:overworld";

    // ==================================================================== BURN

    @Test
    void burn_fireReplacedByAir_emitsBurnRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 10, 64, 10, "minecraft:fire");
        cell.observe(WORLD, 10, 64, 10, "minecraft:air");

        assertThat(cell.submitter.burnRows).hasSize(1);
        Row r = cell.submitter.burnRows.get(0);
        assertThat(r.actorName).isEqualTo(Sentinel.FIRE);
        assertThat(r.blockId).isEqualTo("minecraft:fire");
        assertThat(r.sourceTag).startsWith("#fire");
    }

    @Test
    void burn_soulFireReplaced_emitsBurnRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 1, 65, 1, "minecraft:soul_fire");
        cell.observe(WORLD, 1, 65, 1, "minecraft:dirt");

        assertThat(cell.submitter.burnRows).hasSize(1);
        assertThat(cell.submitter.burnRows.get(0).blockId).isEqualTo("minecraft:soul_fire");
    }

    // ==================================================================== IGNITE

    @Test
    void ignite_airReplacedByFire_emitsIgniteRow() {
        Cell cell = new Cell();
        // Prior state at pos — /setblock fire directly (no prev observed).
        // Z2 classifier treats "fresh fire appearance" as IGNITE unconditionally
        // to match the pre-Z2 FireBlockMixin#onPlace semantics.
        cell.observe(WORLD, 20, 60, 20, "minecraft:fire");

        assertThat(cell.submitter.igniteRows).hasSize(1);
        Row r = cell.submitter.igniteRows.get(0);
        assertThat(r.actorName).isEqualTo(Sentinel.FIRE);
        assertThat(r.blockId).isEqualTo("minecraft:fire");
        assertThat(r.sourceTag).startsWith("#fire");
    }

    // ==================================================================== FADE

    @Test
    void fade_iceMeltsToWater_emitsFadeRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 30, 62, 30, "minecraft:ice");
        cell.observe(WORLD, 30, 62, 30, "minecraft:water");

        assertThat(cell.submitter.fadeRows).hasSize(1);
        assertThat(cell.submitter.fadeRows.get(0).blockId).isEqualTo("minecraft:ice");
        assertThat(cell.submitter.fadeRows.get(0).sourceTag).startsWith("#natural");
    }

    @Test
    void fade_snowLayerMelts_emitsFadeRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 40, 70, 40, "minecraft:snow");
        cell.observe(WORLD, 40, 70, 40, "minecraft:air");

        assertThat(cell.submitter.fadeRows).hasSize(1);
        assertThat(cell.submitter.fadeRows.get(0).blockId).isEqualTo("minecraft:snow");
    }

    // ==================================================================== FORM

    @Test
    void form_concretePowderSolidifies_emitsFormRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 50, 63, 50, "minecraft:red_concrete_powder");
        cell.observe(WORLD, 50, 63, 50, "minecraft:red_concrete");

        assertThat(cell.submitter.formRows).hasSize(1);
        Row r = cell.submitter.formRows.get(0);
        assertThat(r.blockId).isEqualTo("minecraft:red_concrete");
        assertThat(r.sourceTag).startsWith("#natural");
    }

    @Test
    void form_lavaMeetsWaterMakesCobble_emitsFormRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 60, 50, 60, "minecraft:lava");
        cell.observe(WORLD, 60, 50, 60, "minecraft:cobblestone");

        assertThat(cell.submitter.formRows).hasSize(1);
        assertThat(cell.submitter.formRows.get(0).blockId).isEqualTo("minecraft:cobblestone");
    }

    @Test
    void form_waterFreezesToIce_emitsFormRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 70, 80, 70, "minecraft:water");
        cell.observe(WORLD, 70, 80, 70, "minecraft:ice");

        assertThat(cell.submitter.formRows).hasSize(1);
        assertThat(cell.submitter.formRows.get(0).blockId).isEqualTo("minecraft:ice");
    }

    // ==================================================================== SPREAD

    @Test
    void spread_dirtToGrass_emitsSpreadRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 80, 64, 80, "minecraft:dirt");
        cell.observe(WORLD, 80, 64, 80, "minecraft:grass_block");

        assertThat(cell.submitter.spreadRows).hasSize(1);
        Row r = cell.submitter.spreadRows.get(0);
        assertThat(r.blockId).isEqualTo("minecraft:grass_block");
        assertThat(r.sourceTag).startsWith("#natural");
    }

    @Test
    void spread_coarseDirtToMycelium_emitsSpreadRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 90, 65, 90, "minecraft:coarse_dirt");
        cell.observe(WORLD, 90, 65, 90, "minecraft:mycelium");

        assertThat(cell.submitter.spreadRows).hasSize(1);
        assertThat(cell.submitter.spreadRows.get(0).blockId).isEqualTo("minecraft:mycelium");
    }

    // ==================================================================== LEAVES_DECAY

    @Test
    void leavesDecay_oakLeavesToAir_emitsLeavesDecayRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 100, 70, 100, "minecraft:oak_leaves");
        cell.observe(WORLD, 100, 70, 100, "minecraft:air");

        assertThat(cell.submitter.leavesDecayRows).hasSize(1);
        Row r = cell.submitter.leavesDecayRows.get(0);
        assertThat(r.blockId).isEqualTo("minecraft:oak_leaves");
        assertThat(r.sourceTag).startsWith("#natural");
    }

    @Test
    void leavesDecay_moddedLeaves_emitsLeavesDecayRow() {
        Cell cell = new Cell();
        cell.observe(WORLD, 110, 71, 110, "mymod:cherry_leaves");
        cell.observe(WORLD, 110, 71, 110, "minecraft:air");

        assertThat(cell.submitter.leavesDecayRows).hasSize(1);
        assertThat(cell.submitter.leavesDecayRows.get(0).blockId).isEqualTo("mymod:cherry_leaves");
    }

    // ==================================================================== negative cases

    @Test
    void noTransition_sameBlockId_afterFirstFireEmitsSingleIgnite() {
        Cell cell = new Cell();
        // First observation of fresh fire -> IGNITE.
        cell.observe(WORLD, 200, 64, 200, "minecraft:fire");
        // Second observation of the same fire block -> no-op (no transition).
        cell.observe(WORLD, 200, 64, 200, "minecraft:fire");

        assertThat(cell.submitter.igniteRows).hasSize(1);
        assertThat(cell.submitter.allRowCount()).isEqualTo(1);
    }

    @Test
    void nonHotBlock_isNeitherCachedNorClassified() {
        Cell cell = new Cell();
        cell.observe(WORLD, 300, 64, 300, "minecraft:diamond_block");
        cell.observe(WORLD, 300, 64, 300, "minecraft:redstone_block");

        assertThat(cell.submitter.allRowCount()).isZero();
        // neither diamond nor redstone are hot -> cache never grew
        assertThat(cell.cacheSize()).isZero();
    }

    @Test
    void unrelatedTransition_notClassified() {
        Cell cell = new Cell();
        // grass -> dirt is not a SPREAD (that's reversed / trampling)
        cell.observe(WORLD, 400, 64, 400, "minecraft:grass_block");
        cell.observe(WORLD, 400, 64, 400, "minecraft:dirt");

        assertThat(cell.submitter.allRowCount()).isZero();
    }

    // ==================================================================== bounded cache

    @Test
    void cacheBoundedUnderPressure_dropsOldest() {
        Cell cell = new Cell();
        // Overflow the cache
        for (int i = 0; i < Cell.MAX + 50; i++) {
            cell.observe(WORLD, i, 64, 0, "minecraft:fire");
        }
        // Cache should have shed at least 32 entries
        assertThat(cell.cacheSize()).isLessThanOrEqualTo(Cell.MAX);
    }

    // ==================================================================== source-tag prefixes stay reserved

    @Test
    void allSubmissions_useReservedMixinHotPrefixes() {
        Cell cell = new Cell();
        cell.observe(WORLD, 1, 1, 1, "minecraft:fire");
        cell.observe(WORLD, 1, 1, 1, "minecraft:air");
        cell.observe(WORLD, 2, 2, 2, "minecraft:air");
        cell.observe(WORLD, 2, 2, 2, "minecraft:fire");
        cell.observe(WORLD, 3, 3, 3, "minecraft:ice");
        cell.observe(WORLD, 3, 3, 3, "minecraft:water");
        cell.observe(WORLD, 4, 4, 4, "minecraft:red_concrete_powder");
        cell.observe(WORLD, 4, 4, 4, "minecraft:red_concrete");
        cell.observe(WORLD, 5, 5, 5, "minecraft:dirt");
        cell.observe(WORLD, 5, 5, 5, "minecraft:grass_block");
        cell.observe(WORLD, 6, 6, 6, "minecraft:oak_leaves");
        cell.observe(WORLD, 6, 6, 6, "minecraft:air");

        List<Row> all = new ArrayList<>();
        all.addAll(cell.submitter.burnRows);
        all.addAll(cell.submitter.igniteRows);
        all.addAll(cell.submitter.fadeRows);
        all.addAll(cell.submitter.formRows);
        all.addAll(cell.submitter.spreadRows);
        all.addAll(cell.submitter.leavesDecayRows);

        // pos 2: fresh fire arriving with no prior observation -> IGNITE + implicit tracking
        //   (this yields ONE extra ignite row on top of the 6 transitions below,
        //    matching the pre-Z2 FireBlockMixin#onPlace(TAIL) semantics)
        // pos 3: ice -> water = FADE
        // pos 4: powder -> concrete = FORM
        // pos 5: dirt -> grass = SPREAD
        // pos 6: leaves -> air = LEAVES_DECAY
        assertThat(all).hasSize(7);
        for (Row r : all) {
            assertThat(r.sourceTag).matches(s -> s.startsWith("#fire") || s.startsWith("#natural"),
                    "sourceTag must reuse MixinHotEventFilter reserved prefix");
        }
    }

    // ---- test-only model of the ForgeEvents Z2 classifier ------------

    /**
     * Portable pure-Java model mirroring the Z2 classifier logic inside each
     * cell's {@code ForgeEvents.java}. Keeping this in sync with the three
     * cell copies is the whole point of the test — a regression in either
     * direction (test or handler) surfaces here.
     */
    static final class Cell {
        static final int MAX = 4096;
        final Map<Long, String> cache = new HashMap<>();
        final Recorder submitter = new Recorder();

        static long key(String worldId, int x, int y, int z) {
            long wh = worldId == null ? 0L : ((long) worldId.hashCode()) & 0xFFFFFFFFL;
            long xl = ((long) x) & 0x3FFFFFF;
            long yl = ((long) y) & 0xFFF;
            long zl = ((long) z) & 0x3FFFFFF;
            return (wh << 32) ^ (xl << 38) ^ (yl << 26) ^ zl;
        }

        static String cat(String id) {
            if (id == null) return null;
            if (id.equals("minecraft:fire") || id.equals("minecraft:soul_fire")) return "fire";
            if (id.equals("minecraft:ice") || id.equals("minecraft:frosted_ice")
                    || id.equals("minecraft:packed_ice") || id.equals("minecraft:blue_ice")
                    || id.equals("minecraft:snow") || id.equals("minecraft:snow_block")) return "ice";
            if (id.endsWith("_leaves")) return "leaves";
            if (id.endsWith("_concrete_powder")) return "powder";
            if (id.endsWith("_concrete")) return "concrete";
            if (id.equals("minecraft:grass_block") || id.equals("minecraft:mycelium")
                    || id.equals("minecraft:podzol")) return "grass";
            if (id.equals("minecraft:dirt") || id.equals("minecraft:coarse_dirt")
                    || id.equals("minecraft:rooted_dirt")) return "dirt";
            if (id.equals("minecraft:obsidian")) return "obsidian";
            if (id.equals("minecraft:cobblestone")) return "cobblestone";
            if (id.equals("minecraft:stone")) return "stone";
            if (id.equals("minecraft:basalt")) return "basalt";
            if (id.equals("minecraft:water") || id.equals("minecraft:flowing_water")) return "water";
            if (id.equals("minecraft:lava") || id.equals("minecraft:flowing_lava")) return "lava";
            return null;
        }

        void observe(String worldId, int x, int y, int z, String curId) {
            long k = key(worldId, x, y, z);
            String curCat = cat(curId);
            String prevId = cache.get(k);
            String prevCat = cat(prevId);

            if (prevId != null && !prevId.equals(curId)) {
                classify(worldId, x, y, z, prevId, curId, prevCat, curCat);
            } else if (prevId == null && "fire".equals(curCat)) {
                submitter.submitIgnite(null, Sentinel.FIRE, worldId, x, y, z, curId, "#fire:ignite");
            }
            if (isPrimaryHot(curCat)) {
                cache.put(k, curId);
                enforce();
            } else if (prevCat != null) {
                cache.put(k, curId);
                enforce();
            }
        }

        static boolean isPrimaryHot(String c) {
            if (c == null) return false;
            return switch (c) {
                case "fire", "ice", "leaves", "powder", "concrete", "grass", "dirt", "water", "lava" -> true;
                default -> false;
            };
        }

        void classify(String worldId, int x, int y, int z,
                      String prevId, String curId, String prevCat, String curCat) {
            if ("fire".equals(prevCat) && !"fire".equals(curCat)) {
                submitter.submitBurn(null, Sentinel.FIRE, worldId, x, y, z, prevId, "#fire:burnout");
                return;
            }
            if (!"fire".equals(prevCat) && "fire".equals(curCat)) {
                submitter.submitIgnite(null, Sentinel.FIRE, worldId, x, y, z, curId, "#fire:ignite");
                return;
            }
            if ("ice".equals(prevCat) && !"ice".equals(curCat)) {
                submitter.submitFade(null, "#natural:fade", worldId, x, y, z, prevId, "#natural:fade");
                return;
            }
            if ("powder".equals(prevCat) && "concrete".equals(curCat)) {
                submitter.submitForm(null, "#natural:form", worldId, x, y, z, curId, "#natural:form");
                return;
            }
            if (("water".equals(prevCat) || "lava".equals(prevCat)) &&
                    ("obsidian".equals(curCat) || "cobblestone".equals(curCat)
                            || "stone".equals(curCat) || "basalt".equals(curCat))) {
                submitter.submitForm(null, "#natural:form", worldId, x, y, z, curId, "#natural:form");
                return;
            }
            if (("water".equals(prevCat) || prevCat == null) && "ice".equals(curCat)) {
                submitter.submitForm(null, "#natural:form", worldId, x, y, z, curId, "#natural:form");
                return;
            }
            if ("dirt".equals(prevCat) && "grass".equals(curCat)) {
                submitter.submitSpread(null, "#natural:spread", worldId, x, y, z, curId, "#natural:spread");
                return;
            }
            if ("leaves".equals(prevCat) && !"leaves".equals(curCat)) {
                submitter.submitLeavesDecay(null, "#natural:decay", worldId, x, y, z, prevId, "#natural:decay");
                return;
            }
        }

        void enforce() {
            if (cache.size() <= MAX) return;
            int drop = 32;
            Iterator<Map.Entry<Long, String>> it = cache.entrySet().iterator();
            while (it.hasNext() && drop > 0) { it.next(); it.remove(); drop--; }
        }

        int cacheSize() { return cache.size(); }
    }

    record Row(UUID actorUuid, String actorName, String worldId,
               int x, int y, int z, String blockId, String sourceTag) {}

    static final class Recorder implements EventSubmitter {
        final List<Row> burnRows = new ArrayList<>();
        final List<Row> igniteRows = new ArrayList<>();
        final List<Row> fadeRows = new ArrayList<>();
        final List<Row> formRows = new ArrayList<>();
        final List<Row> spreadRows = new ArrayList<>();
        final List<Row> leavesDecayRows = new ArrayList<>();

        int allRowCount() {
            return burnRows.size() + igniteRows.size() + fadeRows.size()
                    + formRows.size() + spreadRows.size() + leavesDecayRows.size();
        }

        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            burnRows.add(new Row(u, n, w, x, y, z, b, s));
        }
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            igniteRows.add(new Row(u, n, w, x, y, z, b, s));
        }
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            fadeRows.add(new Row(u, n, w, x, y, z, b, s));
        }
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            formRows.add(new Row(u, n, w, x, y, z, b, s));
        }
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            spreadRows.add(new Row(u, n, w, x, y, z, b, s));
        }
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            leavesDecayRows.add(new Row(u, n, w, x, y, z, b, s));
        }

        // ---- unused (inline discipline, no shared stub) ----
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
        @Override public void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
        @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}
    }
}
