/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.mixinperf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X2 regression — LightningBolt.spawnFire fire-spread attribution.
 *
 * <p>Ledger's {@code LightningBoltMixin} injects on two ordinals of
 * {@code ServerLevel.setBlockAndUpdate(pos, state)} inside
 * {@code spawnFire}: the impact point (ordinal 0) and each spread attempt
 * (ordinal 1). VG's redirect targets the vanilla mapped signature and
 * auto-multiplexes across all matching invoke sites, so this test needs
 * only exercise one call and verify the shape.</p>
 */
class LightningFireSpreadTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String ACTOR = "#mob:minecraft:lightning_bolt";

    @Test
    void impactFire_submitsWithSourceTag() {
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityPlace(
                rec, ACTOR, WORLD, 50, 70, 100,
                "minecraft:fire", false,
                EntityMixinGuardHarness.SRC_LIGHTNING, true);
        assertThat(rec.entityChangeCount.get()).isEqualTo(1);
        EntityMixinGuardHarness.Recording.Captured c = rec.entityChanges.get(0);
        assertThat(c.sourceTag).isEqualTo("#lightning");
        assertThat(c.newBlockId).isEqualTo("minecraft:fire");
        assertThat(c.oldBlockId).isEqualTo("minecraft:air");
    }

    @Test
    void spreadAttempts_multipleRows() {
        // A single lightning strike fires ordinal-1 up to 4 times as it
        // tries to spread. Each successful placement is one row.
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        // Impact
        EntityMixinGuardHarness.guardedEntityPlace(rec, ACTOR, WORLD, 0, 70, 0,
                "minecraft:fire", false, EntityMixinGuardHarness.SRC_LIGHTNING, true);
        // Spread
        for (int d = 1; d <= 4; d++) {
            EntityMixinGuardHarness.guardedEntityPlace(rec, ACTOR, WORLD, d, 70, 0,
                    "minecraft:fire", false, EntityMixinGuardHarness.SRC_LIGHTNING, true);
        }
        assertThat(rec.entityChangeCount.get()).isEqualTo(5);
    }

    @Test
    void spreadOntoAlreadyBurningTile_setBlockFalse_noSubmit() {
        // If the target is already fire or otherwise refuses replacement,
        // setBlockAndUpdate returns false — no row.
        EntityMixinGuardHarness.Recording rec = new EntityMixinGuardHarness.Recording();
        EntityMixinGuardHarness.guardedEntityPlace(
                rec, ACTOR, WORLD, 0, 70, 0,
                "minecraft:fire", false,
                EntityMixinGuardHarness.SRC_LIGHTNING, false);
        assertThat(rec.entityChangeCount.get()).isZero();
    }
}
