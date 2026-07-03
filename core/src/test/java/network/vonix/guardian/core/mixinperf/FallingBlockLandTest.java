/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X2 regression — FallingBlockEntity fall (break at source) + land (place at destination).
 *
 * <p>Ledger's {@code FallingBlockEntityMixin} installs two callbacks
 * (see {@code entities/FallingBlockEntityMixin.java:22-42}):</p>
 * <ol>
 *   <li>{@code fall} — before source air replacement, capture BREAK at the
 *       origin, tagged {@code Sources.GRAVITY}.</li>
 *   <li>{@code tick} — before landing setBlock, capture PLACE at the target,
 *       tagged {@code Sources.GRAVITY}.</li>
 * </ol>
 *
 * <p>Both halves ship with {@code sourceTag=#gravity}. This test exercises
 * a full sand column: N blocks fall, each producing a break row at the
 * source and a place row at the landing pos.</p>
 */
class FallingBlockLandTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String ACTOR = "#mob:minecraft:falling_block";

    @Test
    void sandFall_produces_breakThenPlace() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();

        // 1) fall side — sand at (100, 70, 200) turns to air
        EntityMixinGuardHarness.guardedEntityBreak(
                rec, "#mob:minecraft:unknown" /* pre-entity: no self */,
                WORLD, 100, 70, 200,
                "minecraft:sand", false,
                EntityMixinGuardHarness.SRC_GRAVITY, true);

        // 2) land side — sand placed at (100, 65, 200)
        EntityMixinGuardHarness.guardedEntityPlace(
                rec, ACTOR, WORLD, 100, 65, 200,
                "minecraft:sand", false,
                EntityMixinGuardHarness.SRC_GRAVITY, true);

        assertThat(rec.entityChangeCount.get()).isEqualTo(2);
        assertThat(rec.entityChanges.get(0).sourceTag).isEqualTo("#gravity");
        assertThat(rec.entityChanges.get(0).newBlockId).isEqualTo("minecraft:air");
        assertThat(rec.entityChanges.get(1).sourceTag).isEqualTo("#gravity");
        assertThat(rec.entityChanges.get(1).oldBlockId).isEqualTo("minecraft:air");
        assertThat(rec.entityChanges.get(1).newBlockId).isEqualTo("minecraft:sand");
    }

    @Test
    void concretePowderColumn_10blocks_20rows() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        for (int i = 0; i < 10; i++) {
            EntityMixinGuardHarness.guardedEntityBreak(
                    rec, "#mob:minecraft:falling_block", WORLD, 0, 100 + i, 0,
                    "minecraft:red_concrete_powder", false,
                    EntityMixinGuardHarness.SRC_GRAVITY, true);
            EntityMixinGuardHarness.guardedEntityPlace(
                    rec, ACTOR, WORLD, 0, 60 + i, 0,
                    "minecraft:red_concrete_powder", false,
                    EntityMixinGuardHarness.SRC_GRAVITY, true);
        }
        assertThat(rec.entityChangeCount.get()).isEqualTo(20);
    }

    @Test
    void voidFall_landSideNeverFires() {
        // Falling into the void: fall side fires (block leaves source), land side never fires
        // (there's no landing target — vanilla discards the entity below y=-64 without setBlock).
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityBreak(
                rec, "#mob:minecraft:falling_block", WORLD, 0, 0, 0,
                "minecraft:gravel", false,
                EntityMixinGuardHarness.SRC_GRAVITY, true);
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        assertThat(rec.entityChanges.get(0).newBlockId).isEqualTo("minecraft:air");
    }
}
