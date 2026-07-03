/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X2 regression — SnowGolem snow-trail placement attribution.
 *
 * <p>Ledger's {@code SnowGolemMixin} intercepts
 * {@code Level.setBlockAndUpdate(pos, snow)} inside {@code aiStep}, tagging
 * the placement with {@code Sources.SNOW_GOLEM}. VG's mixin does the same,
 * routing through {@code entityPlace}. Only real placements (setBlockAndUpdate
 * returned {@code true}) submit rows — otherwise a golem walking through
 * hot biomes or over lava, where placement fails silently every tick, would
 * flood the queue.</p>
 */
class SnowGolemTrailTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String ACTOR = "#mob:minecraft:snow_golem";

    @Test
    void snowLayerPlaced_submitsEntityChangeBlock() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityPlace(
                rec, ACTOR, WORLD, 10, 65, 20,
                "minecraft:snow", false,
                EntityMixinGuardHarness.SRC_SNOW_GOLEM, true);
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        EntityMixinGuardHarness.Recording.Captured c = rec.entityChanges.get(0);
        assertThat(c.sourceTag).isEqualTo("#snow_golem");
        assertThat(c.oldBlockId).isEqualTo("minecraft:air");
        assertThat(c.newBlockId).isEqualTo("minecraft:snow");
        assertThat(c.actorName).isEqualTo(ACTOR);
    }

    @Test
    void hotBiome_setBlockFailed_noSubmit() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        // setBlockAndUpdate returns false — snow can't survive on the biome.
        EntityMixinGuardHarness.guardedEntityPlace(
                rec, ACTOR, WORLD, 10, 65, 20,
                "minecraft:snow", false,
                EntityMixinGuardHarness.SRC_SNOW_GOLEM, false);
        assertThat(rec.entityChangeCount.get()).isZero();
    }

    @Test
    void hundredTickTrail_noDuplicates() {
        // Fires 100 real placements — each should produce exactly one row.
        // Confirms the harness doesn't accidentally double-count.
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        for (int i = 0; i < 100; i++) {
            EntityMixinGuardHarness.guardedEntityPlace(
                    rec, ACTOR, WORLD, i, 65, 20,
                    "minecraft:snow", false,
                    EntityMixinGuardHarness.SRC_SNOW_GOLEM, true);
        }
        assertThat(rec.entityChangeCount.get()).isEqualTo(100);
        assertThat(rec.entityChanges).hasSize(100);
    }
}
