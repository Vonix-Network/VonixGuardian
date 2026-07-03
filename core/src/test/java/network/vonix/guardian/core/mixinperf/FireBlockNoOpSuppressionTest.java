/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W1a P0 regression — FireBlock BURN/IGNITE must NOT be submitted when
 * fire's random tick did not actually consume or replace a block.
 *
 * <p>The pre-1.3.0 FireBlockMixin used {@code @Inject(at HEAD)} on
 * {@code FireBlock.tick}, which fired unconditionally on every random tick.
 * On a burning structure with ~100 fire blocks × 20 tps that's ≥2000 spurious
 * BURN submits/sec on the server thread. This test proves the new
 * {@code @Redirect}-based guard suppresses those no-op ticks.</p>
 */
final class FireBlockNoOpSuppressionTest {

    @Test
    void tick_removeBlock_returns_false_no_BURN_submitted() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        // Simulate 200 random-ticks where fire adjacent to nonflammable stone
        // decides NOT to consume anything (removeBlock returns false).
        for (int i = 0; i < 200; i++) {
            FireGuardHarness.guardedRemoveBurn(
                    rec,
                    "minecraft:overworld", 10, 64, 10,
                    "minecraft:stone", /*oldWasAir=*/ false,
                    /*changed=*/ false);
        }

        assertThat(rec.burnCount.get()).isZero();
        assertThat(rec.igniteCount.get()).isZero();
        assertThat(rec.totalCount.get()).isZero();
    }

    @Test
    void tick_removeBlock_returns_true_but_old_was_air_no_BURN() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        FireGuardHarness.guardedRemoveBurn(
                rec,
                "minecraft:overworld", 10, 64, 10,
                "minecraft:air", /*oldWasAir=*/ true,
                /*changed=*/ true);

        assertThat(rec.burnCount.get()).isZero();
    }

    @Test
    void tick_setBlock_returns_true_but_same_block_no_submit() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        // FireBlock rewrote its own age property — new block is still fire.
        FireGuardHarness.guardedSet(
                rec,
                "minecraft:overworld", 10, 64, 10,
                "minecraft:fire", /*oldWasAir=*/ false,
                "minecraft:fire", /*newIsAir=*/ false,
                /*sameBlock=*/ true,
                /*changed=*/ true);

        assertThat(rec.burnCount.get()).isZero();
        assertThat(rec.igniteCount.get()).isZero();
    }

    @Test
    void onPlace_when_oldState_is_same_block_no_IGNITE() {
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        // Fire replacing fire during property update — must not double-log.
        FireGuardHarness.guardedOnPlace(
                rec,
                "minecraft:overworld", 5, 64, 5,
                "minecraft:fire",
                /*oldStateSameBlock=*/ true,
                /*levelShowsNew=*/ true);

        assertThat(rec.igniteCount.get()).isZero();
    }

    @Test
    void onPlace_when_level_does_not_show_new_block_no_IGNITE() {
        // Something downstream rejected the fire (e.g. water on top) — level
        // observation shows a different block. The TAIL inject must skip.
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        FireGuardHarness.guardedOnPlace(
                rec,
                "minecraft:overworld", 5, 64, 5,
                "minecraft:fire",
                /*oldStateSameBlock=*/ false,
                /*levelShowsNew=*/ false);

        assertThat(rec.igniteCount.get()).isZero();
    }

    @Test
    void simulated_burning_structure_200_random_ticks_zero_submits_when_no_actual_burn() {
        // 100 fire blocks × 2 seconds of ticking, ZERO actually consume anything
        // (e.g. hovering over stone or ash). Old HEAD inject would submit ~2000.
        FireGuardHarness.Recording rec = new FireGuardHarness.Recording();

        for (int block = 0; block < 100; block++) {
            for (int tick = 0; tick < 20 * 2; tick++) {
                // Every single tick: nothing changes.
                FireGuardHarness.guardedRemoveBurn(rec,
                        "minecraft:overworld", block, 64, 0,
                        "minecraft:stone", false, /*changed=*/ false);
            }
        }

        assertThat(rec.burnCount.get()).isZero();
    }
}
