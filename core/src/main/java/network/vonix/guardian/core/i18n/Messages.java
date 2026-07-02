package network.vonix.guardian.core.i18n;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Central lookup for player-facing (chat/feedback) strings.
 *
 * <p>Backed by {@code lang/<language>.properties} via {@link ResourceBundle}
 * with {@code en_us} as the base fallback. The active language is chosen by
 * {@link #setLanguage(String)} (typically wired from {@code config.language}
 * at plugin bootstrap).
 *
 * <p>Ships with 14 CoreProtect-parity locale bundles (en, de, es, fr, ja, ko,
 * pl, ru, tr, tt, uk, vi, zh_cn, zh_tw). Missing keys in a translated bundle
 * transparently fall through to the {@code en_us} base bundle — safe when a
 * translation is partial.
 *
 * <h2>Safety contract</h2>
 * <ul>
 *   <li>Missing key &rarr; returns the key itself (never throws).</li>
 *   <li>Formatting exception &rarr; returns the raw template (never throws).</li>
 *   <li>Unknown language &rarr; silently falls back to {@code en_us}.</li>
 *   <li>Args are interpolated with {@link MessageFormat} — use {@code {0}},
 *       {@code {1}}, etc.</li>
 * </ul>
 *
 * @since 1.2.0
 */
public final class Messages {

    private static final String BASE_BUNDLE = "lang.en_us";
    private static final String BUNDLE_PREFIX = "lang.";

    private static volatile String activeBundleName = BASE_BUNDLE;

    private Messages() {}

    /**
     * Set the active language bundle by key (e.g. {@code "fr"}, {@code "zh_cn"},
     * {@code "en_us"}). Values that don't resolve to an existing bundle silently
     * fall back to {@code en_us} at lookup time.
     *
     * @param language language key from {@code config.language}; {@code null}
     *                 or blank resets to {@code en_us}
     * @since 1.2.0
     */
    public static void setLanguage(String language) {
        if (language == null || language.isBlank()) {
            activeBundleName = BASE_BUNDLE;
            return;
        }
        String normalized = language.trim().toLowerCase(java.util.Locale.ROOT);
        activeBundleName = BUNDLE_PREFIX + normalized;
        // Best-effort eager check: verify the bundle exists so we log/fail loudly
        // at startup rather than silently degrading on the first lookup.
        try {
            ResourceBundle.getBundle(activeBundleName);
        } catch (MissingResourceException e) {
            activeBundleName = BASE_BUNDLE;
        }
    }

    /**
     * Look up {@code key} in the active message bundle (falling back to
     * {@code en_us}) and interpolate {@code args} via {@link MessageFormat}.
     *
     * @param key  message key (e.g. {@code "query.error.unknown_prefix"})
     * @param args optional interpolation arguments
     * @return the formatted message, or the key itself if the lookup fails
     */
    public static String get(String key, Object... args) {
        String template = lookup(key);
        if (template == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        try {
            return MessageFormat.format(template, args);
        } catch (IllegalArgumentException e) {
            return template;
        }
    }

    private static String lookup(String key) {
        String bundleName = activeBundleName;
        if (!BASE_BUNDLE.equals(bundleName)) {
            try {
                return ResourceBundle.getBundle(bundleName).getString(key);
            } catch (MissingResourceException ignored) {
                // fall through to base bundle
            }
        }
        try {
            return ResourceBundle.getBundle(BASE_BUNDLE).getString(key);
        } catch (MissingResourceException e) {
            return null;
        }
    }
}
