/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.attribution;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.diagnostics.MixinHotEventFilter;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X3 — end-to-end regression for the bucket-empty → fluid-spread
 * traceback pipeline. Mimics what a loader-side {@code LiquidBlockMixin}
 * bridge does when a spread cell fires within 2 minutes of a nearby
 * bucket-empty by the same player.
 */
class BucketSpillTracebackTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void spreadWithinWindowAttributesToEmptyingPlayer() {
        FluidSourceMemory mem = new FluidSourceMemory();
        RecordingSubmitter s = new RecordingSubmitter();
        UUID alice = UUID.randomUUID();

        // t=0: Alice empties a water bucket at (100, 64, 100)
        long t0 = 10_000L;
        mem.recordBucketEmpty(WORLD, 100, 64, 100, alice, "Alice", t0);

        // t=+30s: 4 blocks of water spread away — must attribute to Alice
        long tSpread = t0 + 30_000L;
        for (int dx = 1; dx <= 4; dx++) {
            submitFluidSpread(s, mem, WORLD, 100 + dx, 64, 100, "minecraft:water", tSpread);
        }

        assertThat(s.rows).hasSize(4);
        for (RecordingSubmitter.Row r : s.rows) {
            assertThat(r.actorUuid).isEqualTo(alice);
            assertThat(r.actorName).isEqualTo("Alice");
            assertThat(r.sourceTag).startsWith(MixinHotEventFilter.PREFIX_FLUID);
        }
    }

    @Test
    void spreadPastTtlFallsBackToFluidSentinel() {
        FluidSourceMemory mem = new FluidSourceMemory();
        RecordingSubmitter s = new RecordingSubmitter();

        long t0 = 0L;
        mem.recordBucketEmpty(WORLD, 0, 64, 0, UUID.randomUUID(), "Bob", t0);
        long tLate = t0 + FluidSourceMemory.TTL_MS + 5_000L;
        submitFluidSpread(s, mem, WORLD, 3, 64, 0, "minecraft:water", tLate);

        assertThat(s.rows).hasSize(1);
        assertThat(s.rows.get(0).actorUuid).isNull();
        assertThat(s.rows.get(0).actorName).isEqualTo(Sentinel.FLUID);
    }

    @Test
    void spreadBeyondRadiusFallsBackToFluidSentinel() {
        FluidSourceMemory mem = new FluidSourceMemory();
        RecordingSubmitter s = new RecordingSubmitter();

        mem.recordBucketEmpty(WORLD, 0, 64, 0, UUID.randomUUID(), "Carol", 0L);
        // Manhattan 9 — outside 8-block window.
        submitFluidSpread(s, mem, WORLD, 5, 64, 4, "minecraft:water", 1_000L);

        assertThat(s.rows).hasSize(1);
        assertThat(s.rows.get(0).actorUuid).isNull();
        assertThat(s.rows.get(0).actorName).isEqualTo(Sentinel.FLUID);
    }

    @Test
    void lavaAndWaterCarryDistinctSourceTagSuffixes() {
        FluidSourceMemory mem = new FluidSourceMemory();
        RecordingSubmitter s = new RecordingSubmitter();

        submitFluidSpread(s, mem, WORLD, 0, 64, 0, "minecraft:water", 100L);
        submitFluidSpread(s, mem, WORLD, 1, 64, 0, "minecraft:lava", 100L);

        assertThat(s.rows).hasSize(2);
        assertThat(s.rows.get(0).sourceTag).isEqualTo(MixinHotEventFilter.PREFIX_FLUID + ":water");
        assertThat(s.rows.get(1).sourceTag).isEqualTo(MixinHotEventFilter.PREFIX_FLUID + ":lava");
    }

    @Test
    void fluidSourceTagTriggersMixinHotEventFilter() {
        assertThat(MixinHotEventFilter.isMixinSourced(MixinHotEventFilter.PREFIX_FLUID + ":water")).isTrue();
        assertThat(MixinHotEventFilter.isMixinSourced(MixinHotEventFilter.PREFIX_FLUID + ":lava")).isTrue();
        assertThat(MixinHotEventFilter.isMixinSourced(Sentinel.FLUID)).isTrue();
    }

    // ---- helper: mimic what the loader mixin bridge does ---------------

    private static void submitFluidSpread(EventSubmitter s, FluidSourceMemory mem,
                                          String worldId, int x, int y, int z,
                                          String fluidBlockId, long nowMs) {
        String kind = fluidBlockId.contains("lava") ? "lava" : "water";
        String sourceTag = MixinHotEventFilter.PREFIX_FLUID + ":" + kind;
        UUID actorUuid = null;
        String actorName = Sentinel.FLUID;
        FluidSourceMemory.Record rec = mem.lookup(worldId, x, y, z, nowMs);
        if (rec != null && rec.actorUuid != null) {
            actorUuid = rec.actorUuid;
            actorName = rec.actorName != null ? rec.actorName : Sentinel.FLUID;
        }
        s.submitFluidFlow(actorUuid, actorName, worldId, x, y, z, fluidBlockId, sourceTag);
    }

    // ---- inline recording submitter (no shared stub — per test-discipline convention) ----

    private static final class RecordingSubmitter implements EventSubmitter {
        record Row(UUID actorUuid, String actorName, String worldId,
                   int x, int y, int z, String fluidId, String sourceTag) {}
        final List<Row> rows = new ArrayList<>();

        @Override public void submitFluidFlow(UUID actorUuid, String actorName, String worldId,
                                              int x, int y, int z, String fluidBlockId, String sourceTag) {
            rows.add(new Row(actorUuid, actorName, worldId, x, y, z, fluidBlockId, sourceTag));
        }
        @Override public void submit(Action a) {}
        @Override public void submitBlockBreak(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
        @Override public void submitBlockPlace(UUID u, String n, String w, int x, int y, int z, String b, String s) {}
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
        @Override public void submitHopperPush(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitHopperPull(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitItemCraft(UUID u, String n, String w, int x, int y, int z, String i, int a, String s) {}
        @Override public void submitEntitySpawn(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitEntityInteract(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingPlace(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitHangingBreak(UUID u, String n, String w, int x, int y, int z, String e, String s) {}
        @Override public void submitStructureGrow(UUID u, String n, String w, int x, int y, int z, String st, String s) {}
        @Override public void submitClick(UUID u, String n, String w, int x, int y, int z, String t, String s) {}
    }
}
