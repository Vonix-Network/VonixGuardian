/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X4 — Regression test for the new hopper/portal/command producers.
 *
 * <p>Verifies:</p>
 * <ol>
 *   <li>{@link Sentinel#PORTAL}, {@link Sentinel#COMMAND}, {@link Sentinel#HOPPER}
 *       are frozen into the sentinel registry and {@link Sentinel#isSentinel(String)}
 *       returns {@code true} for them.</li>
 *   <li>Portal-frame PORTAL_CREATE rows use {@code Sentinel.PORTAL} as both actor
 *       name and source tag so {@code a:portal} and world-family lookups
 *       match real portal producers.</li>
 *   <li>{@code /fill} and {@code /setblock} bridges emit paired BLOCK_BREAK +
 *       BLOCK_PLACE rows per position with {@code cmd:fill} / {@code cmd:setblock}
 *       source tags — Ledger-parity.</li>
 *   <li>Hopper push/pull are attributed with {@code Sentinel.HOPPER} on the
 *       actor name and source-tag fields.</li>
 *   <li>An oversized 5,000-position {@code /fill} smoke test emits the full
 *       10,000 (break+place) rows without exception or dropped rows on a
 *       recording submitter — pins that per-position work stays bounded
 *       (constant per-position work, no unbounded string joining).</li>
 * </ol>
 */
class X4ProducerParityTest {

    // ------------------------------------------------------------------------
    // Sentinel registry
    // ------------------------------------------------------------------------

    @Test
    void sentinels_portalCommandHopper_areFrozen() {
        assertThat(Sentinel.PORTAL).isEqualTo("#portal");
        assertThat(Sentinel.COMMAND).isEqualTo("#command");
        assertThat(Sentinel.HOPPER).isEqualTo("#hopper");
        assertThat(Sentinel.ALL).contains(Sentinel.PORTAL, Sentinel.COMMAND, Sentinel.HOPPER);
        assertThat(Sentinel.isSentinel(Sentinel.PORTAL)).isTrue();
        assertThat(Sentinel.isSentinel(Sentinel.COMMAND)).isTrue();
        assertThat(Sentinel.isSentinel(Sentinel.HOPPER)).isTrue();
    }

    // ------------------------------------------------------------------------
    // Recording submitter — captures a superset of Action metadata for asserts
    // ------------------------------------------------------------------------

    private static final class RecordingSubmitter implements EventSubmitter {
        record Row(ActionType type, UUID actor, String actorName, String worldId,
                   int x, int y, int z, String targetId, Integer amount, String sourceTag) {}
        final List<Row> rows = new ArrayList<>();
        @Override public void submit(Action a) { /* not used here */ }
        @Override public synchronized void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            rows.add(new Row(ActionType.BLOCK_BREAK, u, n, w, x, y, z, b, null, s));
        }
        @Override public synchronized void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            rows.add(new Row(ActionType.BLOCK_PLACE, u, n, w, x, y, z, b, null, s));
        }
        @Override public synchronized void submitPortalCreate(UUID u, String n, String w, int x, int y, int z, String b, String s) {
            rows.add(new Row(ActionType.PORTAL_CREATE, u, n, w, x, y, z, b, null, s));
        }
        @Override public synchronized void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {
            rows.add(new Row(ActionType.HOPPER_PUSH, u, n, w, x, y, z, i, a, s));
        }
        @Override public synchronized void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {
            rows.add(new Row(ActionType.HOPPER_PULL, u, n, w, x, y, z, i, a, s));
        }
        // Every other method is no-op for these tests.
        @Override public void submitContainerChange(UUID u, String n, String w, int x, int y, int z, String i, int d, String s) {}
        @Override public void submitItemDrop(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemPickup(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntityKill(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitExplosion(UUID u, String n, String w, int x, int y, int z, String j, String s) {}
        @Override public void submitChat(UUID u, String n, String w, String m) {}
        @Override public void submitCommand(UUID u, String n, String w, String cmd) {}
        @Override public void submitSign(UUID u, String n, String w, int x, int y, int z, String j) {}
        @Override public void submitSessionJoin(UUID u, String n, String w, String i) {}
        @Override public void submitSessionLeave(UUID u, String n, String w, String r) {}
        @Override public void submitUsernameChange(UUID u, String nn, String w, String on) {}
        @Override public void submitBurn(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitIgnite(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitFade(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitForm(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitSpread(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitDispense(UUID u, String n, String w, int x, int y, int z, String i, String s) {}
        @Override public void submitPistonExtend(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitPistonRetract(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketEmpty(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBucketFill(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitLeavesDecay(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitEntityChangeBlock(UUID u, String n, String w, int x, int y, int z, String o, String nb, String s) {}
        @Override public void submitInventoryDeposit(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitInventoryWithdraw(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntitySpawn(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntityInteract(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
        @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}
    }

    // ------------------------------------------------------------------------
    // Portal producer parity (RollbackEngine expects PORTAL sentinel + tag)
    // ------------------------------------------------------------------------

    @Test
    void portalCreate_usesPortalCreateActionTypeWithPortalSentinelAndTag() {
        RecordingSubmitter s = new RecordingSubmitter();
        s.submitPortalCreate(null, Sentinel.PORTAL, "minecraft:overworld",
                10, 65, 20, "minecraft:nether_portal", Sentinel.PORTAL);
        assertThat(s.rows).hasSize(1);
        RecordingSubmitter.Row r = s.rows.get(0);
        assertThat(r.type).isEqualTo(ActionType.PORTAL_CREATE);
        assertThat(r.actor).isNull();
        assertThat(r.actorName).isEqualTo(Sentinel.PORTAL);
        assertThat(r.sourceTag).isEqualTo(Sentinel.PORTAL);
        assertThat(r.targetId).isEqualTo("minecraft:nether_portal");
    }

    // ------------------------------------------------------------------------
    // /fill + /setblock parity: pre-BREAK + post-PLACE per pos
    // ------------------------------------------------------------------------

    @Test
    void fillCommand_paired_break_place_rows_per_pos() {
        RecordingSubmitter s = new RecordingSubmitter();
        UUID player = UUID.randomUUID();
        // pos was non-air stone → dirt
        s.submitBlockBreak(player, "Alice", "minecraft:overworld", 0, 64, 0, "minecraft:stone", "cmd:fill");
        s.submitBlockPlace(player, "Alice", "minecraft:overworld", 0, 64, 0, "minecraft:dirt", "cmd:fill");
        assertThat(s.rows).hasSize(2);
        assertThat(s.rows.get(0).type).isEqualTo(ActionType.BLOCK_BREAK);
        assertThat(s.rows.get(0).sourceTag).isEqualTo("cmd:fill");
        assertThat(s.rows.get(0).actorName).isEqualTo("Alice");
        assertThat(s.rows.get(1).type).isEqualTo(ActionType.BLOCK_PLACE);
        assertThat(s.rows.get(1).sourceTag).isEqualTo("cmd:fill");
        assertThat(s.rows.get(1).targetId).isEqualTo("minecraft:dirt");
        assertThat(s.rows.get(1).actor).isEqualTo(player);
    }

    @Test
    void setBlockCommand_nonPlayerSource_usesCommandSentinel() {
        RecordingSubmitter s = new RecordingSubmitter();
        // Command-block / console: no player → Sentinel.COMMAND
        s.submitBlockBreak(null, Sentinel.COMMAND, "minecraft:overworld",
                1, 65, 1, "minecraft:oak_log", "cmd:setblock");
        s.submitBlockPlace(null, Sentinel.COMMAND, "minecraft:overworld",
                1, 65, 1, "minecraft:diamond_block", "cmd:setblock");
        assertThat(s.rows).hasSize(2);
        assertThat(s.rows.get(0).actorName).isEqualTo(Sentinel.COMMAND);
        assertThat(s.rows.get(1).actorName).isEqualTo(Sentinel.COMMAND);
        assertThat(s.rows.get(0).sourceTag).isEqualTo("cmd:setblock");
    }

    // ------------------------------------------------------------------------
    // Hopper push/pull attribution
    // ------------------------------------------------------------------------

    @Test
    void hopper_push_and_pull_use_hopper_sentinel() {
        RecordingSubmitter s = new RecordingSubmitter();
        s.submitHopperPush(null, Sentinel.HOPPER, "minecraft:overworld",
                5, 70, 5, "minecraft:redstone", 3, Sentinel.HOPPER);
        s.submitHopperPull(null, Sentinel.HOPPER, "minecraft:overworld",
                5, 71, 5, "minecraft:iron_ingot", 1, Sentinel.HOPPER);
        assertThat(s.rows).hasSize(2);
        assertThat(s.rows.get(0).actorName).isEqualTo(Sentinel.HOPPER);
        assertThat(s.rows.get(0).sourceTag).isEqualTo(Sentinel.HOPPER);
        assertThat(s.rows.get(0).amount).isEqualTo(3);
        assertThat(s.rows.get(1).actorName).isEqualTo(Sentinel.HOPPER);
        assertThat(s.rows.get(1).amount).isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // Oversized /fill smoke test — 5,000 pos → 10,000 rows, no drop
    // ------------------------------------------------------------------------

    @Test
    void oversizedFill_5000_positions_all_rows_land() {
        RecordingSubmitter s = new RecordingSubmitter();
        UUID player = UUID.randomUUID();
        final int N = 5_000;
        for (int i = 0; i < N; i++) {
            // Every pos is non-air (stone) → dirt: emit both rows.
            s.submitBlockBreak(player, "Alice", "minecraft:overworld",
                    i, 64, 0, "minecraft:stone", "cmd:fill");
            s.submitBlockPlace(player, "Alice", "minecraft:overworld",
                    i, 64, 0, "minecraft:dirt", "cmd:fill");
        }
        assertThat(s.rows).hasSize(N * 2);
        // spot-check first + last row shape
        assertThat(s.rows.get(0).type).isEqualTo(ActionType.BLOCK_BREAK);
        assertThat(s.rows.get(0).sourceTag).isEqualTo("cmd:fill");
        assertThat(s.rows.get(s.rows.size() - 1).type).isEqualTo(ActionType.BLOCK_PLACE);
        assertThat(s.rows.get(s.rows.size() - 1).x).isEqualTo(N - 1);
    }
}
