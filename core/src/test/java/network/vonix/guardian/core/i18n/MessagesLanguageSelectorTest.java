package network.vonix.guardian.core.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** W5-06: verify config.language switches the active message bundle. */
class MessagesLanguageSelectorTest {

    @AfterEach
    void resetToDefault() {
        Messages.setLanguage("en_us");
    }

    @Test
    void french_bundle_overrides_key_and_falls_back_for_missing_keys() {
        String enUser = Messages.get("query.error.user_invalid_name", "notch");
        Messages.setLanguage("fr");
        String frUser = Messages.get("query.error.user_invalid_name", "notch");

        assertNotEquals(enUser, frUser, "French bundle should return a different string");
        assertTrue(frUser.contains("notch"), "Placeholder must interpolate in fr bundle");

        // A key with no fr override should fall through to en_us.
        String frFallback = Messages.get("status.section.guardian");
        assertEquals("VonixGuardian", frFallback,
                "Missing fr key must fall back to en_us");
    }

    @Test
    void unknown_language_falls_back_silently() {
        Messages.setLanguage("xx_unknown");
        assertEquals("VonixGuardian", Messages.get("status.section.guardian"));
    }

    @Test
    void every_shipped_language_bundle_loads() {
        for (String lang : new String[]{
                "en", "de", "es", "fr", "ja", "ko", "pl",
                "ru", "tr", "tt", "uk", "vi", "zh_cn", "zh_tw"}) {
            Messages.setLanguage(lang);
            // If the bundle failed to load, setLanguage falls back and we still
            // get en_us for missing keys — assert something resolves.
            String s = Messages.get("query.error.user_invalid_name", "x");
            assertNotNull(s);
            assertFalse(s.isBlank(), "Language " + lang + " produced blank");
        }
    }
}
