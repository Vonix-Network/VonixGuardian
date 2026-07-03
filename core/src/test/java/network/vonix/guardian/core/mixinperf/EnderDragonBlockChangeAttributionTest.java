/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X2 regression — EnderDragon head-collision block break attribution.
 *
 * <p>Ledger's {@code EnderDragonMixin} injects on
 * {@code ServerLevel.removeBlock(pos, Z)} inside {@code checkWalls}. Our
 * mixin uses the same target but as a guarded {@code @Redirect} so a return
 * of {@code false} (block was air, protected, or already gone) does NOT
 * produce a row. This test pins those behaviors:</p>
 * <ol>
 *   <li>Successful break of a stone block → 1 ENTITY_CHANGE_BLOCK with
 *       {@code sourceTag=#enderdragon}, {@code oldBlockId=minecraft:stone},
 *       {@code newBlockId=minecraft:air}, actor={@code #mob:minecraft:ender_dragon}.</li>
 *   <li>{@code removeBlock} returned {@code false} → 0 rows.</li>
 *   <li>{@code oldWasAir=true} (block was actually air pre-remove) → 0 rows.</li>
 * </ol>
 */
class EnderDragonBlockChangeAttributionTest {

    private static final String WORLD = "minecraft:the_end";
    private static final String ACTOR = "#mob:minecraft:ender_dragon";

    @Test
    void headCollisionBreaksStone_submitsEntityChangeBlock() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        boolean returned = EntityMixinGuardHarness.guardedEntityBreak(
                rec, ACTOR, WORLD, 100, 65, -200,
                "minecraft:stone", false,
                EntityMixinGuardHarness.SRC_ENDER_DRAGON,
                /* changed */ true);

        assertThat(returned).isTrue();
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        assertThat(rec.entityChanges).hasSize(1);
        EntityMixinGuardHarness.Recording.Captured c = rec.entityChanges.get(0);
        assertThat(c.actorName).isEqualTo(ACTOR);
        assertThat(c.worldId).isEqualTo(WORLD);
        assertThat(c.x).isEqualTo(100);
        assertThat(c.y).isEqualTo(65);
        assertThat(c.z).isEqualTo(-200);
        assertThat(c.oldBlockId).isEqualTo("minecraft:stone");
        assertThat(c.newBlockId).isEqualTo("minecraft:air");
        assertThat(c.sourceTag).isEqualTo("#enderdragon");
    }

    @Test
    void removeBlockReturnedFalse_noSubmit() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        boolean returned = EntityMixinGuardHarness.guardedEntityBreak(
                rec, ACTOR, WORLD, 0, 64, 0,
                "minecraft:bedrock", false,
                EntityMixinGuardHarness.SRC_ENDER_DRAGON,
                /* changed */ false);

        assertThat(returned).isFalse();
        assertThat(rec.entityChangeCount.get()).isZero();
        assertThat(rec.totalCount.get()).isZero();
    }

    @Test
    void airBlock_noSubmit() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        boolean returned = EntityMixinGuardHarness.guardedEntityBreak(
                rec, ACTOR, WORLD, 0, 200, 0,
                "minecraft:air", true,
                EntityMixinGuardHarness.SRC_ENDER_DRAGON,
                /* changed */ true);

        assertThat(returned).isTrue();
        assertThat(rec.entityChangeCount.get()).isZero();
    }

    @Test
    void sourceTagIsStable() {
        // Pins the exact tag string operators will filter on.
        assertThat(EntityMixinGuardHarness.SRC_ENDER_DRAGON).isEqualTo("#enderdragon");
    }
}
