/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the centralised vanilla-griefer whitelist.
 *
 * <p>Covers WAVE-AUDIT-1.1.5 A3 (stripMobPrefix consolidation) and A5
 * (removal of dead {@code wind_charge} / {@code breeze_wind_charge} entries).
 */
class VanillaGrieferSetTest {

    @Test
    void stripMobPrefix_strips_mob_sentinel() {
        assertEquals("minecraft:creeper",
                VanillaGrieferSet.stripMobPrefix("#mob:minecraft:creeper"));
        assertEquals("iceandfire:fire_dragon",
                VanillaGrieferSet.stripMobPrefix("#mob:iceandfire:fire_dragon"));
    }

    @Test
    void stripMobPrefix_returns_null_for_non_sentinel() {
        assertNull(VanillaGrieferSet.stripMobPrefix(null));
        assertNull(VanillaGrieferSet.stripMobPrefix(""));
        assertNull(VanillaGrieferSet.stripMobPrefix("minecraft:creeper"));
        assertNull(VanillaGrieferSet.stripMobPrefix("Steve"));
        // Wrong prefix — must not partial-match.
        assertNull(VanillaGrieferSet.stripMobPrefix("mob:minecraft:creeper"));
    }

    @Test
    void stripMobPrefix_preserves_empty_suffix() {
        assertEquals("", VanillaGrieferSet.stripMobPrefix("#mob:"));
    }

    @Test
    void wind_charge_entries_are_removed_from_default_allowlist() {
        // WAVE-AUDIT-1.1.5 A5: both entries are Projectile, never satisfy
        // LivingDestroyBlockEvent's LivingEntity dispatch, so they were dead code.
        assertFalse(VanillaGrieferSet.DEFAULT_ALLOWLIST.contains("minecraft:wind_charge"),
                "wind_charge must not be in the default allowlist (dead entry removed 1.1.6)");
        assertFalse(VanillaGrieferSet.DEFAULT_ALLOWLIST.contains("minecraft:breeze_wind_charge"),
                "breeze_wind_charge must not be in the default allowlist (dead entry removed 1.1.6)");
    }

    @Test
    void shouldRecord_rejects_wind_charge_by_default() {
        assertFalse(VanillaGrieferSet.shouldRecord("minecraft:wind_charge", null, false));
        assertFalse(VanillaGrieferSet.shouldRecord("minecraft:breeze_wind_charge", List.of(), false));
    }

    @Test
    void shouldRecord_rejects_canonical_vanilla_griefers_by_default_on_forge_event() {
        // Bukkit/CoreProtect listens to real EntityChangeBlockEvent. Forge's
        // LivingDestroyBlockEvent is only a prospective permission check and can
        // fire millions of times on modded packs (Berk/HTTYD). Fail closed unless
        // the operator explicitly opts an entity in.
        assertFalse(VanillaGrieferSet.shouldRecord("minecraft:enderman", null, false));
        assertFalse(VanillaGrieferSet.shouldRecord("minecraft:ravager", null, false));
        assertFalse(VanillaGrieferSet.shouldRecord("minecraft:ender_dragon", null, false));
    }

    @Test
    void shouldRecord_null_and_empty_are_rejected() {
        assertFalse(VanillaGrieferSet.shouldRecord(null, null, false));
        assertFalse(VanillaGrieferSet.shouldRecord("", null, false));
    }

    @Test
    void shouldRecord_config_additions_are_honoured() {
        assertTrue(VanillaGrieferSet.shouldRecord("iceandfire:fire_dragon",
                List.of("iceandfire:fire_dragon"), false));
    }

    @Test
    void shouldRecord_logAllEntities_bypasses_the_whitelist() {
        assertTrue(VanillaGrieferSet.shouldRecord("modded:whatever", null, true));
        // Even the dead wind_charge slips through when the admin explicitly opts in.
        assertTrue(VanillaGrieferSet.shouldRecord("minecraft:wind_charge", null, true));
    }
}
