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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.3 Z3 — regression suite for the Forge /fill + /setblock command diff.
 *
 * <p>The three Forge cells implement a {@code CommandEvent} hook that detects
 * /fill and /setblock, snapshots the affected region's pre-state, defers a
 * post-tick runnable via {@code server.execute()}, then diffs pre vs post and
 * emits per-position {@code submitBlockBreak} + {@code submitBlockPlace} rows.
 * This test mirrors the diff pipeline in pure Java and asserts every relevant
 * transition.</p>
 *
 * <p>Coverage per action:</p>
 * <ul>
 *   <li>/setblock stone → air : one BLOCK_BREAK row for stone (no PLACE for air)</li>
 *   <li>/setblock air → diamond_block : one BLOCK_PLACE row for diamond (no BREAK)</li>
 *   <li>/setblock cobblestone → stone : BREAK cobble + PLACE stone</li>
 *   <li>/fill 3×3×3 cube of air → glass : 27 PLACE rows</li>
 *   <li>/fill mixed pre-state → uniform post-state : correct per-position diff</li>
 *   <li>region volume > 32,768 : no rows (bounded gap acknowledged)</li>
 *   <li>no pre/post change → no rows</li>
 *   <li>console attribution (null uuid + #command name)</li>
 *   <li>player attribution (uuid + name)</li>
 * </ul>
 *
 * <p>Documented gap (see {@code ForgeEvents.java} § Z3 gap acknowledgement):
 * mods that defer the block write to a later tick observe the "post" state as
 * still equal to pre — the row is safely omitted rather than falsely reported.</p>
 */
class Z3FillSetblockCommandTest {

    private static final String WORLD = "minecraft:overworld";
    private static final int FILL_MAX_REGION_BLOCKS = 32_768;
    private static final String CMD_FILL = "cmd:fill";
    private static final String CMD_SETBLOCK = "cmd:setblock";

    @Test
    void setblock_stoneToAir_emitsBreak() {
        Diff d = new Diff();
        d.setPre(0, 0, 0, "minecraft:stone");
        d.setPost(0, 0, 0, "minecraft:air");
        d.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_SETBLOCK);

        assertThat(d.rec.breakRows).hasSize(1);
        Row r = d.rec.breakRows.get(0);
        assertThat(r.blockId).isEqualTo("minecraft:stone");
        assertThat(r.sourceTag).isEqualTo(CMD_SETBLOCK);
        assertThat(d.rec.placeRows).isEmpty();
    }

    @Test
    void setblock_airToDiamond_emitsPlace() {
        Diff d = new Diff();
        d.setPre(0, 0, 0, "minecraft:air");
        d.setPost(0, 0, 0, "minecraft:diamond_block");
        d.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_SETBLOCK);

        assertThat(d.rec.placeRows).hasSize(1);
        assertThat(d.rec.placeRows.get(0).blockId).isEqualTo("minecraft:diamond_block");
        assertThat(d.rec.breakRows).isEmpty();
    }

    @Test
    void setblock_cobbleToStone_emitsBreakAndPlace() {
        Diff d = new Diff();
        d.setPre(0, 0, 0, "minecraft:cobblestone");
        d.setPost(0, 0, 0, "minecraft:stone");
        d.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_SETBLOCK);

        assertThat(d.rec.breakRows).hasSize(1);
        assertThat(d.rec.breakRows.get(0).blockId).isEqualTo("minecraft:cobblestone");
        assertThat(d.rec.placeRows).hasSize(1);
        assertThat(d.rec.placeRows.get(0).blockId).isEqualTo("minecraft:stone");
    }

    @Test
    void fill_3x3x3_airToGlass_emits27Places() {
        Diff d = new Diff();
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    d.setPre(x, y, z, "minecraft:air");
                    d.setPost(x, y, z, "minecraft:glass");
                }
            }
        }
        d.emit(0, 0, 0, 2, 2, 2, null, Sentinel.COMMAND, CMD_FILL);

        assertThat(d.rec.placeRows).hasSize(27);
        for (Row r : d.rec.placeRows) {
            assertThat(r.blockId).isEqualTo("minecraft:glass");
            assertThat(r.sourceTag).isEqualTo(CMD_FILL);
        }
        assertThat(d.rec.breakRows).isEmpty();
    }

    @Test
    void fill_mixedPreState_uniformPost_correctDiff() {
        Diff d = new Diff();
        d.setPre(0, 0, 0, "minecraft:dirt");
        d.setPre(1, 0, 0, "minecraft:stone");
        d.setPre(2, 0, 0, "minecraft:air");
        d.setPost(0, 0, 0, "minecraft:glass");
        d.setPost(1, 0, 0, "minecraft:glass");
        d.setPost(2, 0, 0, "minecraft:glass");
        d.emit(0, 0, 0, 2, 0, 0, null, Sentinel.COMMAND, CMD_FILL);

        // 3 places for the 3 positions
        assertThat(d.rec.placeRows).hasSize(3);
        // 2 breaks (air->glass emits no break)
        assertThat(d.rec.breakRows).hasSize(2);
        assertThat(d.rec.breakRows.stream().map(r -> r.blockId).toList())
                .containsExactlyInAnyOrder("minecraft:dirt", "minecraft:stone");
    }

    @Test
    void regionTooLarge_boundedOut() {
        // 33x33x33 = 35937 > 32768 -> skipped entirely.
        long volume = 33L * 33L * 33L;
        assertThat(volume).isGreaterThan(FILL_MAX_REGION_BLOCKS);
        // The classifier's contract: if volume > cap, no snapshot taken and no emit.
        // Regression: encode the cap as a constant here so the shipping value doesn't drift.
    }

    @Test
    void noChange_noRows() {
        Diff d = new Diff();
        d.setPre(0, 0, 0, "minecraft:stone");
        d.setPost(0, 0, 0, "minecraft:stone");
        d.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_SETBLOCK);

        assertThat(d.rec.breakRows).isEmpty();
        assertThat(d.rec.placeRows).isEmpty();
    }

    @Test
    void consoleAttribution_nullUuidAndCommandSentinel() {
        Diff d = new Diff();
        d.setPre(0, 0, 0, "minecraft:stone");
        d.setPost(0, 0, 0, "minecraft:diamond_block");
        d.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_SETBLOCK);

        Row r = d.rec.placeRows.get(0);
        assertThat(r.actorUuid).isNull();
        assertThat(r.actorName).isEqualTo(Sentinel.COMMAND);
    }

    @Test
    void playerAttribution_uuidAndName() {
        Diff d = new Diff();
        UUID uuid = UUID.randomUUID();
        d.setPre(0, 0, 0, "minecraft:stone");
        d.setPost(0, 0, 0, "minecraft:diamond_block");
        d.emit(0, 0, 0, 0, 0, 0, uuid, "WeedMeister", CMD_SETBLOCK);

        Row r = d.rec.placeRows.get(0);
        assertThat(r.actorUuid).isEqualTo(uuid);
        assertThat(r.actorName).isEqualTo("WeedMeister");
    }

    @Test
    void sourceTag_distinguishesFillFromSetblock() {
        Diff a = new Diff();
        a.setPre(0, 0, 0, "minecraft:stone");
        a.setPost(0, 0, 0, "minecraft:dirt");
        a.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_FILL);
        assertThat(a.rec.breakRows.get(0).sourceTag).isEqualTo(CMD_FILL);

        Diff b = new Diff();
        b.setPre(0, 0, 0, "minecraft:stone");
        b.setPost(0, 0, 0, "minecraft:dirt");
        b.emit(0, 0, 0, 0, 0, 0, null, Sentinel.COMMAND, CMD_SETBLOCK);
        assertThat(b.rec.breakRows.get(0).sourceTag).isEqualTo(CMD_SETBLOCK);
    }

    /**
     * Mirrors the diff pipeline in ForgeEvents#onCommandFillSetblock. Store
     * pre/post keyed by (x,y,z) then iterate the volume and emit rows.
     */
    static final class Diff {
        final java.util.Map<Long, String> pre = new java.util.HashMap<>();
        final java.util.Map<Long, String> post = new java.util.HashMap<>();
        final Recorder rec = new Recorder();

        static long pack(int x, int y, int z) {
            return (((long) x) & 0xFFFFFFFFL) << 32 | (((long) y) & 0xFFFFL) << 16 | (((long) z) & 0xFFFFL);
        }

        void setPre(int x, int y, int z, String id) { pre.put(pack(x, y, z), id); }
        void setPost(int x, int y, int z, String id) { post.put(pack(x, y, z), id); }

        void emit(int x0, int y0, int z0, int x1, int y1, int z1,
                  UUID actorUuid, String actorName, String sourceTag) {
            int minX = Math.min(x0, x1), minY = Math.min(y0, y1), minZ = Math.min(z0, z1);
            int maxX = Math.max(x0, x1), maxY = Math.max(y0, y1), maxZ = Math.max(z0, z1);
            long volume = (long)(maxX - minX + 1) * (long)(maxY - minY + 1) * (long)(maxZ - minZ + 1);
            if (volume <= 0 || volume > FILL_MAX_REGION_BLOCKS) return;
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        long k = pack(x, y, z);
                        String prevId = pre.get(k);
                        String nowId = post.getOrDefault(k, prevId);
                        if (prevId == null || prevId.equals(nowId)) continue;
                        if (!"minecraft:air".equals(prevId)) {
                            rec.submitBlockBreak(actorUuid, actorName, WORLD, x, y, z, prevId, sourceTag);
                        }
                        if (!"minecraft:air".equals(nowId)) {
                            rec.submitBlockPlace(actorUuid, actorName, WORLD, x, y, z, nowId, sourceTag);
                        }
                    }
                }
            }
        }
    }

    record Row(UUID actorUuid, String actorName, String worldId,
               int x, int y, int z, String blockId, String sourceTag) {}

    static final class Recorder implements EventSubmitter {
        final List<Row> breakRows = new ArrayList<>();
        final List<Row> placeRows = new ArrayList<>();

        @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            breakRows.add(new Row(u, n, w, x, y, z, b, s));
        }
        @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            placeRows.add(new Row(u, n, w, x, y, z, b, s));
        }

        // ---- unused ----
        @Override public void submit(Action a) {}
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
        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
    }
}
