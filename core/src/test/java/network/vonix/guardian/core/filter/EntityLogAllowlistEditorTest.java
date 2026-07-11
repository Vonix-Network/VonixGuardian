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

/** Unit tests for the {@code /vg entitylog} edit logic (v1.4.0). */
class EntityLogAllowlistEditorTest {

    @Test
    void normalize_accepts_the_three_valid_shapes() {
        assertEquals("isleofberk:night_fury",
                EntityLogAllowlistEditor.normalize("isleofberk:night_fury"));
        assertEquals("isleofberk:*", EntityLogAllowlistEditor.normalize("isleofberk:*"));
        assertEquals("isleofberk", EntityLogAllowlistEditor.normalize("isleofberk"));
    }

    @Test
    void normalize_trims_and_lowercases() {
        assertEquals("isleofberk:night_fury",
                EntityLogAllowlistEditor.normalize("  IsleOfBerk:Night_Fury  "));
        assertEquals("isleofberk:*", EntityLogAllowlistEditor.normalize("ISLEOFBERK:*"));
    }

    @Test
    void normalize_rejects_malformed_input() {
        assertNull(EntityLogAllowlistEditor.normalize(null));
        assertNull(EntityLogAllowlistEditor.normalize(""));
        assertNull(EntityLogAllowlistEditor.normalize("   "));
        assertNull(EntityLogAllowlistEditor.normalize("*"));
        assertNull(EntityLogAllowlistEditor.normalize(":*"));
        assertNull(EntityLogAllowlistEditor.normalize(":foo"));
        assertNull(EntityLogAllowlistEditor.normalize("foo:"));
        assertNull(EntityLogAllowlistEditor.normalize("a:b:c"));
        assertNull(EntityLogAllowlistEditor.normalize("has space"));
        assertNull(EntityLogAllowlistEditor.normalize("mod:with space"));
    }

    @Test
    void add_appends_new_entry() {
        var r = EntityLogAllowlistEditor.add(List.of(), "isleofberk:*");
        assertTrue(r.changed());
        assertEquals(List.of("isleofberk:*"), r.allowlist());
    }

    @Test
    void add_is_idempotent() {
        var r = EntityLogAllowlistEditor.add(List.of("isleofberk:*"), "isleofberk:*");
        assertFalse(r.changed());
        assertEquals(List.of("isleofberk:*"), r.allowlist());
        assertTrue(r.message().toLowerCase().contains("already"));
    }

    @Test
    void add_preserves_order_and_normalizes() {
        var r = EntityLogAllowlistEditor.add(List.of("mca:*"), "IsleOfBerk:*");
        assertTrue(r.changed());
        assertEquals(List.of("mca:*", "isleofberk:*"), r.allowlist());
    }

    @Test
    void add_rejects_invalid_without_mutating() {
        var r = EntityLogAllowlistEditor.add(List.of("mca:*"), "*");
        assertFalse(r.changed());
        assertEquals(List.of("mca:*"), r.allowlist());
        assertTrue(r.message().toLowerCase().contains("invalid"));
    }

    @Test
    void remove_deletes_existing_entry() {
        var r = EntityLogAllowlistEditor.remove(List.of("a:*", "b:*"), "a:*");
        assertTrue(r.changed());
        assertEquals(List.of("b:*"), r.allowlist());
    }

    @Test
    void remove_missing_is_noop() {
        var r = EntityLogAllowlistEditor.remove(List.of("a:*"), "b:*");
        assertFalse(r.changed());
        assertEquals(List.of("a:*"), r.allowlist());
        assertTrue(r.message().toLowerCase().contains("not whitelisted"));
    }

    @Test
    void remove_normalizes_token_for_match() {
        var r = EntityLogAllowlistEditor.remove(List.of("isleofberk:*"), "  IsleOfBerk:*  ");
        assertTrue(r.changed());
        assertEquals(List.of(), r.allowlist());
    }

    @Test
    void currentOrEmpty_handles_null() {
        assertEquals(List.of(), EntityLogAllowlistEditor.currentOrEmpty(null));
    }

    @Test
    void added_entry_actually_matches_via_VanillaGrieferSet() {
        var r = EntityLogAllowlistEditor.add(List.of(), "isleofberk:*");
        assertTrue(VanillaGrieferSet.shouldRecord("isleofberk:night_fury", r.allowlist(), false));
    }
}
