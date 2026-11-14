package network.vonix.guardian.core.theme;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Static registry of built-in {@link Theme}s.
 *
 * <p>Themes are looked up by lower-case name. Unknown names resolve to the
 * default {@link #AQUA} theme — callers never receive null.</p>
 *
 * <p>This class holds only constants; no mutable state.</p>
 */
public final class ThemeRegistry {

    /**
     * Default theme. The block id uses {@code secondary}. The old
     * {@code #00AAAA} was 3.85:1 on a mid-grey chat background, which is below
     * WCAG AA, and it disappeared against a bright world. Ids are near-white
     * so the chat box and Minecraft's text shadow can both carry them.
     */
    public static final Theme AQUA = new Theme(
        "aqua", "#55FFFF", "#F4FFFF", "#55FF55", "#FF9999", "#FFAA00", "#D0D0D0");

    /** Cool blue palette. */
    public static final Theme BLUE = new Theme(
        "blue", "#AAAAFF", "#DDDDFF", "#55FF55", "#FF9999", "#FFAA00", "#D0D0D0");

    /** Warm gold palette. */
    public static final Theme GOLD = new Theme(
        "gold", "#FFAA00", "#FFFF99", "#55FF55", "#FF9999", "#FFCC66", "#D0D0D0");

    /** Green palette. */
    public static final Theme GREEN = new Theme(
        "green", "#55FF55", "#C8FFC8", "#FFFF55", "#FF9999", "#FFAA00", "#D0D0D0");

    /** Purple palette. */
    public static final Theme PURPLE = new Theme(
        "purple", "#FF99FF", "#FFCCFF", "#55FF55", "#FF9999", "#FFAA00", "#D0D0D0");

    /** Red palette. */
    public static final Theme RED = new Theme(
        "red", "#FF9999", "#FFCCCC", "#55FF55", "#FFCC66", "#FFFF55", "#D0D0D0");

    /** Neutral white palette. */
    public static final Theme WHITE = new Theme(
        "white", "#FFFFFF", "#E8E8E8", "#55FF55", "#FF9999", "#FFAA00", "#D0D0D0");

    private static final Map<String, Theme> THEMES;

    static {
        Map<String, Theme> m = new LinkedHashMap<>();
        m.put(AQUA.name(),   AQUA);
        m.put(BLUE.name(),   BLUE);
        m.put(GOLD.name(),   GOLD);
        m.put(GREEN.name(),  GREEN);
        m.put(PURPLE.name(), PURPLE);
        m.put(RED.name(),    RED);
        m.put(WHITE.name(),  WHITE);
        THEMES = Collections.unmodifiableMap(m);
    }

    private ThemeRegistry() {
        // utility class
    }

    /**
     * Outcome of looking a configured theme name up: the theme that will
     * actually be used, the name that was asked for, and whether the two
     * agreed.
     *
     * <p>{@link #get(String)} has always fallen back to {@link #AQUA} for an
     * unknown name. That is the right behaviour for a renderer (it must never
     * hand out {@code null}), but it meant a typo in {@code config.json}
     * produced a default-looking server with no signal that the setting had
     * been ignored. Callers that report configuration &mdash; {@code /vg
     * status}, {@code /vg reload} &mdash; use this to say so out loud.</p>
     *
     * @param requested the name from configuration, verbatim; may be {@code null}
     * @param theme     the theme that will be used; never {@code null}
     * @param honoured  {@code true} only when {@code requested} named a registered theme
     */
    public record Resolution(String requested, Theme theme, boolean honoured) {

        /** A human-readable name for what was asked for, never blank. */
        public String requestedOrPlaceholder() {
            return requested == null || requested.isBlank() ? "(not set)" : requested;
        }
    }

    /**
     * Resolve a configured theme name, reporting whether the name was actually
     * recognised. Prefer this over {@link #get(String)} anywhere the answer is
     * shown to an operator, so a silently-ignored setting cannot masquerade as
     * a working one.
     *
     * @param name theme identifier from configuration; may be {@code null}
     * @return the applied theme plus whether the request was honoured; never {@code null}
     */
    public static Resolution resolve(String name) {
        Theme theme = get(name);
        boolean honoured = name != null && !name.isBlank()
                && THEMES.containsKey(name.toLowerCase(Locale.ROOT));
        return new Resolution(name, theme, honoured);
    }

    /**
     * Returns {@code theme} when it is non-null, otherwise the default
     * {@link #AQUA}. A renderer that accepts a nullable theme has no way to
     * distinguish "no theme chosen" from "a theme was forgotten"; routing
     * through this makes the fallback explicit and testable.
     *
     * @param theme a possibly-null theme
     * @return a non-null theme
     */
    public static Theme orDefault(Theme theme) {
        return theme != null ? theme : AQUA;
    }

    /**
     * Returns the theme with the given name, or {@link #AQUA} if {@code name} is
     * null, blank, or unknown.
     *
     * @param name theme identifier; matched case-insensitively
     * @return the matched theme, never null
     */
    public static Theme get(String name) {
        if (name == null || name.isBlank()) {
            return AQUA;
        }
        Theme t = THEMES.get(name.toLowerCase(Locale.ROOT));
        return t != null ? t : AQUA;
    }

    /**
     * Returns true if a theme with the given (case-insensitive) name is registered.
     *
     * @param name theme identifier
     * @return whether the name resolves to a registered theme
     */
    public static boolean has(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return THEMES.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns all registered themes in insertion order.
     *
     * @return an unmodifiable collection of every built-in theme
     */
    public static Collection<Theme> all() {
        return THEMES.values();
    }
}
