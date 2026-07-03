/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X2 regression — Silverfish infest attribution.
 *
 * <p>Ledger's {@code silverfish/WanderAndInfestGoalMixin} wraps
 * {@code LevelAccessor.setBlock(pos, state, flags)} inside the merge-with-stone
 * goal and produces a full old→new state row (stone → infested_stone). VG's
 * mixin uses the {@code entityChange} dispatcher so both {@code oldBlockId}
 * and {@code newBlockId} are populated (unlike the break-then-place shape).</p>
 */
class SilverfishInfestTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String ACTOR = "#mob:minecraft:silverfish";

    @Test
    void stoneInfested_submitsFullStateChange() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityChange(
                rec, ACTOR, WORLD, 12, 40, 34,
                "minecraft:stone", "minecraft:infested_stone",
                EntityMixinGuardHarness.SRC_SILVERFISH, true);
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        EntityMixinGuardHarness.Recording.Captured c = rec.entityChanges.get(0);
        assertThat(c.sourceTag).isEqualTo("#silverfish");
        assertThat(c.oldBlockId).isEqualTo("minecraft:stone");
        assertThat(c.newBlockId).isEqualTo("minecraft:infested_stone");
        assertThat(c.actorName).isEqualTo(ACTOR);
    }

    @Test
    void mossyCobblestoneInfested_submitsMossyInfested() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityChange(
                rec, ACTOR, WORLD, 0, 40, 0,
                "minecraft:mossy_cobblestone", "minecraft:infested_mossy_cobblestone",
                EntityMixinGuardHarness.SRC_SILVERFISH, true);
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        assertThat(rec.entityChanges.get(0).newBlockId)
                .isEqualTo("minecraft:infested_mossy_cobblestone");
    }

    @Test
    void setBlockFailed_noSubmit() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityChange(
                rec, ACTOR, WORLD, 0, 40, 0,
                "minecraft:stone", "minecraft:infested_stone",
                EntityMixinGuardHarness.SRC_SILVERFISH, false);
        assertThat(rec.entityChangeCount.get()).isZero();
    }

    @Test
    void sourceTagIsStable() {
        assertThat(EntityMixinGuardHarness.SRC_SILVERFISH).isEqualTo("#silverfish");
    }
}
