package network.vonix.guardian.core.i18n;

import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Key-parity and placeholder-integrity contract for every shipped locale bundle.
 *
 * <h2>Why this test exists</h2>
 * {@link Messages} falls through from a translated bundle to {@code en_us} for
 * missing keys, so a half-filled bundle is <em>silent</em>: the operator sees
 * English and nobody notices the locale is broken. That hides exactly the class
 * of defect this test makes loud:
 *
 * <ul>
 *   <li>a key present in {@code en_us} but missing from a locale (silent fallback);</li>
 *   <li>a key present only in a locale (dead weight that can never be rendered);</li>
 *   <li>a locale string that <b>drops a {@code {0}} placeholder</b> the call site
 *       passes — the offending value disappears from the operator's error message,
 *       which is worse than an untranslated string because it looks translated.</li>
 * </ul>
 *
 * <p>The placeholder defect was not hypothetical: before this test existed, the 13
 * non-English bundles dropped the argument from {@code radius_not_integer},
 * {@code radius_negative}, {@code action_required} and {@code action_unknown}
 * (56 losses in total).
 *
 * <h2>Why these files are read directly, not through {@link java.util.ResourceBundle}</h2>
 * {@code ResourceBundle} caches by base name and builds a parent chain, so once any
 * other test has loaded {@code lang.de} the cached bundle resolves missing keys from
 * {@code en_us} and its {@code keySet()} contains the base keys too. A parity check
 * run against that bundle passes even when the file on disk is missing half its
 * keys. Reading the file is the only way to assert what is actually <em>shipped</em>.
 * {@link #every_known_language_is_selectable_at_runtime()} covers the runtime side.
 *
 * <p>Deliberately structural, not a text pin: it asserts the documented contract
 * (keys and placeholders) and never pins a specific translation. Rewording a bundle
 * must not fail this test; changing its key set or losing a placeholder must.
 */
class LangBundleParityTest {

    /** The base bundle every other bundle is measured against. */
    private static final String BASE = "en_us";

    /** Load one shipped bundle exactly as it exists on disk, with no inheritance. */
    private static Properties loadShipped(String language) {
        String resource = "lang/" + language + ".properties";
        Properties props = new Properties();
        try (InputStream in = LangBundleParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return fail("language '" + language + "' is accepted by config.language "
                        + "(GuardianConfig.KNOWN_LANGUAGES) but '" + resource
                        + "' is not on the classpath — that locale silently renders en_us");
            }
            // JDK 9+ reads PropertyResourceBundle as UTF-8, and these files are
            // written as ASCII with \\uXXXX escapes, so UTF-8 is correct either way.
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return fail("could not read " + resource + ": " + e);
        }
        return props;
    }

    /** Every language accepted by {@code config.language}, which must each have a bundle. */
    private static Set<String> knownLanguages() {
        assertFalse(GuardianConfig.KNOWN_LANGUAGES.isEmpty(), "KNOWN_LANGUAGES must not be empty");
        assertTrue(GuardianConfig.KNOWN_LANGUAGES.contains(BASE),
                "en_us must be a selectable language — it is the base bundle");
        return new TreeSet<>(GuardianConfig.KNOWN_LANGUAGES);
    }

    /**
     * Every language {@code config.language} accepts must have a real bundle.
     *
     * <p>One direction only, deliberately: a language accepted by config but with
     * no file renders English while claiming to be translated, which is the
     * defect that matters. The reverse case — a stray {@code .properties} file
     * that no config value selects — cannot be checked reliably from the
     * classpath without assuming an exploded directory (it is a jar in
     * production), so it is not asserted here. Such a file is inert: nothing can
     * select it, and the key-set test still proves it is not silently shadowing
     * a real locale.
     */
    @Test
    void every_language_config_accepts_has_a_non_empty_bundle() {
        Set<String> known = knownLanguages();
        assertTrue(known.size() >= 15,
                () -> "expected at least 15 shipped locales, config accepts " + known);
        for (String lang : known) {
            Properties props = loadShipped(lang);
            assertFalse(props.isEmpty(),
                    () -> "bundle lang/" + lang + ".properties exists but contributes no keys");
        }
    }

    /** Full key parity in both directions against {@code en_us}. */
    @Test
    void every_bundle_has_exactly_the_en_us_key_set() {
        Set<String> expected = new TreeSet<>(loadShipped(BASE).stringPropertyNames());
        assertFalse(expected.isEmpty(), "en_us bundle must not be empty");

        List<String> problems = new ArrayList<>();
        for (String lang : knownLanguages()) {
            Set<String> actual = new TreeSet<>(loadShipped(lang).stringPropertyNames());
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new TreeSet<>(actual);
            extra.removeAll(expected);
            if (!missing.isEmpty()) {
                problems.add(lang + ": missing " + missing);
            }
            if (!extra.isEmpty()) {
                problems.add(lang + ": unexpected " + extra);
            }
        }
        if (!problems.isEmpty()) {
            fail("key parity with " + BASE + ".properties is broken. A missing key falls "
                    + "back to en_us, so this is invisible at runtime:\n  "
                    + String.join("\n  ", problems));
        }
    }

    /**
     * A locale string must carry the same numbered placeholders as {@code en_us}.
     *
     * <p>Extra placeholders are a failure too: the call site passes a fixed argument
     * list, so a {@code {2}} on a two-argument key renders literally into chat.
     */
    @Test
    void every_bundle_preserves_en_us_placeholders() {
        Properties base = loadShipped(BASE);
        List<String> problems = new ArrayList<>();

        for (String lang : knownLanguages()) {
            Properties bundle = loadShipped(lang);
            for (String key : new TreeSet<>(base.stringPropertyNames())) {
                if (!bundle.containsKey(key)) {
                    continue; // reported by the key-set test
                }
                Set<String> want = placeholders(base.getProperty(key));
                Set<String> got = placeholders(bundle.getProperty(key));
                if (!want.equals(got)) {
                    problems.add(lang + " / " + key + ": expected placeholders " + want
                            + " but found " + got);
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("a translated string that drops a placeholder removes the offending "
                    + "value from the operator's error message:\n  "
                    + String.join("\n  ", problems));
        }
    }

    /**
     * Every value must be a usable {@link MessageFormat} pattern at its call-site
     * arity, and must actually emit each argument.
     *
     * <p>Catches unpaired single quotes, which MessageFormat tolerates by silently
     * quoting the rest of the pattern — the placeholder then never renders.
     */
    @Test
    void every_bundle_value_renders_at_its_call_site_arity() {
        Properties base = loadShipped(BASE);
        List<String> problems = new ArrayList<>();

        for (String lang : knownLanguages()) {
            Properties bundle = loadShipped(lang);
            for (String key : new TreeSet<>(base.stringPropertyNames())) {
                if (!bundle.containsKey(key)) {
                    continue;
                }
                String template = bundle.getProperty(key);
                int arity = placeholders(base.getProperty(key)).size();
                Object[] args = new Object[arity];
                for (int i = 0; i < arity; i++) {
                    args[i] = "ARG" + i;
                }
                String rendered;
                try {
                    rendered = MessageFormat.format(template, args);
                } catch (IllegalArgumentException e) {
                    problems.add(lang + " / " + key + ": not a valid MessageFormat pattern ("
                            + e.getMessage() + "): " + template);
                    continue;
                }
                for (int i = 0; i < arity; i++) {
                    if (!rendered.contains("ARG" + i)) {
                        problems.add(lang + " / " + key + ": argument {" + i
                                + "} never reaches chat: \"" + template + "\" rendered \"" + rendered + "\"");
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("bundle values must render their arguments:\n  " + String.join("\n  ", problems));
        }
    }

    /** No bundle may ship an empty value — that renders as a blank chat line. */
    @Test
    void no_bundle_has_a_blank_value() {
        List<String> problems = new ArrayList<>();
        for (String lang : knownLanguages()) {
            Properties bundle = loadShipped(lang);
            for (String key : new TreeSet<>(bundle.stringPropertyNames())) {
                String value = bundle.getProperty(key);
                if (value == null || value.isBlank()) {
                    problems.add(lang + " / " + key);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "blank values render as empty chat lines:\n  "
                + String.join("\n  ", problems));
    }

    /** Keys must look like dotted identifiers, so the parity diff stays readable. */
    @Test
    void every_key_is_a_dotted_identifier() {
        List<String> problems = new ArrayList<>();
        for (String lang : knownLanguages()) {
            for (String key : new TreeSet<>(loadShipped(lang).stringPropertyNames())) {
                if (!key.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")) {
                    problems.add(lang + " / " + key);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "malformed message keys:\n  "
                + String.join("\n  ", problems));
    }

    /**
     * Unpaired single quotes are the most common Properties/MessageFormat mistake.
     * Check the raw text rather than trusting the formatter, which tolerates them
     * by quoting the remainder of the pattern.
     */
    @Test
    void every_bundle_doubles_its_single_quotes() {
        List<String> problems = new ArrayList<>();
        for (String lang : knownLanguages()) {
            for (String key : new TreeSet<>(loadShipped(lang).stringPropertyNames())) {
                String value = loadShipped(lang).getProperty(key);
                if (value.indexOf('\'') < 0) {
                    continue;
                }
                if (value.replace("''", "").indexOf('\'') >= 0) {
                    problems.add(lang + " / " + key + ": " + value);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> "a lone single quote makes MessageFormat "
                + "quote the rest of the pattern; double it as '':\n  "
                + String.join("\n  ", problems));
    }

    /**
     * Runtime wiring: every accepted language must select a bundle that overrides
     * at least one en_us value. Guards the case where a bundle is present but
     * {@link Messages#setLanguage(String)} cannot resolve it (a silently-English
     * locale), without pinning what the translation says.
     */
    @Test
    void every_known_language_is_selectable_at_runtime() {
        List<String> problems = new ArrayList<>();
        String key = "query.error.user_empty";

        // 'en' is an English alias and 'tt' is an acknowledged English-placeholder
        // bundle, so neither is required to differ from en_us.
        Set<String> mayMatchEnglish = Set.of(BASE, "en", "tt");

        // Measure the en_us baseline from the file, not through Messages, so this
        // test does not depend on whatever language a previously-run test left set.
        String enValue = loadShipped(BASE).getProperty(key);
        assertNotNull(enValue, "en_us must define " + key);

        for (String lang : knownLanguages()) {
            Messages.setLanguage(lang);
            String inLang = Messages.get("query.error.user_invalid_name", "probe");
            if (inLang == null || inLang.isBlank()) {
                problems.add(lang + ": resolves to blank");
            } else if (!inLang.contains("probe")) {
                problems.add(lang + ": placeholder {0} disappeared at runtime: " + inLang);
            }
            if (!mayMatchEnglish.contains(lang) && enValue.equals(Messages.get(key))) {
                problems.add(lang + ": '" + key + "' still renders en_us — bundle not selected");
            }
        }
        Messages.setLanguage(BASE);
        assertTrue(problems.isEmpty(), () -> "runtime language selection is broken:\n  "
                + String.join("\n  ", problems));
    }

    /**
     * Extract the distinct {@code {n}} placeholders from a pattern, following
     * {@link MessageFormat}'s own quoting rule: a lone {@code '} opens a quoted
     * literal (so a brace inside it is not a placeholder), while {@code ''} is an
     * escaped apostrophe and does not open one.
     *
     * <p>A naive "is the previous char a quote?" check gets this backwards for the
     * common {@code ''{0}''} idiom, which is an escaped apostrophe followed by a
     * real placeholder.
     */
    private static Set<String> placeholders(String pattern) {
        Set<String> found = new TreeSet<>();
        boolean inQuote = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'') {
                    i++; // escaped apostrophe, not a quote delimiter
                } else {
                    inQuote = !inQuote;
                }
                continue;
            }
            if (c != '{' || inQuote) {
                continue;
            }
            int close = pattern.indexOf('}', i);
            if (close < 0) {
                break;
            }
            String body = pattern.substring(i + 1, close);
            if (body.matches("\\d+")) {
                found.add(body);
                i = close;
            }
        }
        return found;
    }
}
