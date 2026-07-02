package network.vonix.guardian.core.i18n;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Central lookup for player-facing (chat/feedback) strings.
 *
 * <p>Backed by {@code lang/en_us.properties} via {@link ResourceBundle}.
 * Locale is fixed to English in v1.2.0; the pluggable-locale flip is
 * scheduled for v1.3.0.
 *
 * <h2>Safety contract</h2>
 * <ul>
 *   <li>Missing key &rarr; returns the key itself (never throws).</li>
 *   <li>Formatting exception &rarr; returns the raw template (never throws).</li>
 *   <li>Args are interpolated with {@link MessageFormat} — use {@code {0}},
 *       {@code {1}}, etc.</li>
 * </ul>
 *
 * <p><b>Scope:</b> only strings that reach a player (chat feedback, command
 * errors, /vg status headers). Log strings stay hardcoded — operators read
 * English logs.
 *
 * @since 1.2.0
 */
public final class Messages {

    private static final String BUNDLE_NAME = "lang.en_us";

    private Messages() {}

    /**
     * Look up {@code key} in the English message bundle and interpolate
     * {@code args} via {@link MessageFormat}.
     *
     * @param key  message key (e.g. {@code "query.error.unknown_prefix"})
     * @param args optional interpolation arguments
     * @return the formatted message, or the key itself if the lookup fails
     */
    public static String get(String key, Object... args) {
        String template;
        try {
            template = ResourceBundle.getBundle(BUNDLE_NAME).getString(key);
        } catch (MissingResourceException e) {
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
}
