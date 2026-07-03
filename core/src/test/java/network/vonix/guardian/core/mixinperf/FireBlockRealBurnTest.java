/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W1a — proves the guarded {@code @Redirect} path DOES submit BURN /
 * IGNITE when fire actually consumed / ignited a block. Complement to
 * {@link FireBlockNoOpSuppressionTest}.
 */
final class FireBlockRealBurnTest {

    @Test
    void tick_removeBlock_returns_true_and_plank_consumed_submits_1_BURN_with_correct_blockId() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        boolean forwarded = FireGuardHarness.guardedRemoveBurn(
                rec,
                "minecraft:overworld", 100, 64, 100,
                "minecraft:oak_planks", /*oldWasAir=*/ false,
                /*changed=*/ true);

        assertThat(forwarded).isTrue();
        assertThat(rec.burnCount.get()).isEqualTo(1);
        assertThat(rec.igniteCount.get()).isZero();
        assertThat(rec.burnBlockIds).containsExactly("minecraft:oak_planks");
    }

    @Test
    void tick_setBlock_replacing_plank_with_air_submits_BURN_with_old_blockId() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        FireGuardHarness.guardedSet(
                rec,
                "minecraft:overworld", 12, 65, 34,
                "minecraft:spruce_planks", /*oldWasAir=*/ false,
                "minecraft:air", /*newIsAir=*/ true,
                /*sameBlock=*/ false,
                /*changed=*/ true);

        assertThat(rec.burnCount.get()).isEqualTo(1);
        assertThat(rec.burnBlockIds).containsExactly("minecraft:spruce_planks");
    }

    @Test
    void tick_setBlock_replacing_plank_with_fire_submits_IGNITE_with_new_blockId() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        FireGuardHarness.guardedSet(
                rec,
                "minecraft:overworld", 12, 65, 34,
                "minecraft:oak_planks", /*oldWasAir=*/ false,
                "minecraft:fire",       /*newIsAir=*/ false,
                /*sameBlock=*/ false,
                /*changed=*/ true);

        assertThat(rec.burnCount.get()).isZero();
        assertThat(rec.igniteCount.get()).isEqualTo(1);
        assertThat(rec.igniteBlockIds).containsExactly("minecraft:fire");
    }

    @Test
    void onPlace_when_level_shows_new_fire_block_submits_IGNITE_once() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        FireGuardHarness.guardedOnPlace(
                rec,
                "minecraft:overworld", 5, 64, 5,
                "minecraft:fire",
                /*oldStateSameBlock=*/ false,
                /*levelShowsNew=*/ true);

        assertThat(rec.igniteCount.get()).isEqualTo(1);
        assertThat(rec.igniteBlockIds).containsExactly("minecraft:fire");
    }

    @Test
    void simulated_10pct_burn_rate_matches_modded_server_observation() {
        // Realistic scenario: 100 fire blocks, 60s, 20 tps. Random-tick chance
        // to actually consume a block is ~10% per attempt on modded servers.
        // We assert the guarded path submits ROUGHLY N × 10% times, and every
        // submit carries the correct old-block id.
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();
        long attempts = 100L * 20 * 60; // 120_000

        for (long i = 0; i < attempts; i++) {
            boolean actuallyBurned = (i % 10 == 0); // deterministic 10%
            FireGuardHarness.guardedRemoveBurn(rec,
                    "minecraft:overworld", (int) (i % 128), 64, 0,
                    "minecraft:oak_planks", false, actuallyBurned);
        }

        assertThat(rec.burnCount.get()).isEqualTo(12_000);
        assertThat(rec.burnBlockIds).allMatch("minecraft:oak_planks"::equals);
    }
}
