/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X2 regression — Ravager crop / leaf destruction attribution.
 *
 * <p>Ledger's {@code RavagerMixin} intercepts
 * {@code Level.destroyBlock(pos, drop, entity)} inside {@code aiStep}. VG's
 * mixin wraps it as a guarded {@code @Redirect} and only submits when the
 * call actually destroyed a block. Verifies:</p>
 * <ol>
 *   <li>A destroyed wheat crop produces exactly one row with
 *       {@code sourceTag=#ravager}, {@code newBlockId=minecraft:air},
 *       actor {@code #mob:minecraft:ravager}.</li>
 *   <li>{@code destroyBlock} returned {@code false} (protected) → 0 rows.</li>
 * </ol>
 */
class RavagerCropDestroyTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String ACTOR = "#mob:minecraft:ravager";

    @Test
    void wheatDestroyed_submitsEntityChangeBlock() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityBreak(
                rec, ACTOR, WORLD, 12, 64, 34,
                "minecraft:wheat", false,
                EntityMixinGuardHarness.SRC_RAVAGER, true);

        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        EntityMixinGuardHarness.Recording.Captured c = rec.entityChanges.get(0);
        assertThat(c.sourceTag).isEqualTo("#ravager");
        assertThat(c.oldBlockId).isEqualTo("minecraft:wheat");
        assertThat(c.newBlockId).isEqualTo("minecraft:air");
        assertThat(c.actorName).isEqualTo(ACTOR);
    }

    @Test
    void protectedRegion_destroyBlockFalse_noSubmit() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityBreak(
                rec, ACTOR, WORLD, 0, 64, 0,
                "minecraft:wheat", false,
                EntityMixinGuardHarness.SRC_RAVAGER, false);
        assertThat(rec.entityChangeCount.get()).isZero();
    }

    @Test
    void leavesDestroyed_submitsEntityChangeBlock() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityBreak(
                rec, ACTOR, WORLD, 100, 70, 200,
                "minecraft:oak_leaves", false,
                EntityMixinGuardHarness.SRC_RAVAGER, true);
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        assertThat(rec.entityChanges.get(0).oldBlockId).isEqualTo("minecraft:oak_leaves");
    }
}
