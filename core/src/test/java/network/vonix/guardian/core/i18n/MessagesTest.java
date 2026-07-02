package network.vonix.guardian.core.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link Messages}.
 */
class MessagesTest {

    @Test
    void known_key_returns_bundle_value() {
        String s = Messages.get("status.section.guardian");
        assertEquals("VonixGuardian", s);
    }

    @Test
    void missing_key_falls_back_to_key() {
        String s = Messages.get("this.key.does.not.exist.ever");
        assertEquals("this.key.does.not.exist.ever", s);
    }

    @Test
    void interpolates_positional_args() {
        String s = Messages.get("query.error.duration_bad_number", "1x", "1x");
        assertNotNull(s);
        assertTrue(s.contains("1x"), () -> "expected substituted arg in: " + s);
    }

    @Test
    void malformed_pattern_does_not_throw() {
        // Missing key -> returns key. Also: passing args when template has no
        // placeholders is fine (MessageFormat ignores extras).
        String s = Messages.get("status.section.storage", "extra");
        assertEquals("Storage", s);
    }

    @Test
    void every_query_error_key_present() {
        // Cheap smoke test — every extracted key should resolve to something
        // that isn't just the key itself.
        String[] keys = {
            "query.error.expected_prefix",
            "query.error.unknown_prefix",
            "query.error.user_empty",
            "query.error.radius_required",
            "query.error.action_required",
            "query.error.flag_unknown",
            "query.error.bad_token_wrap"
        };
        for (String k : keys) {
            String v = Messages.get(k, "x", "y");
            assertTrue(!v.equals(k), () -> "unresolved key: " + k);
        }
    }
}
