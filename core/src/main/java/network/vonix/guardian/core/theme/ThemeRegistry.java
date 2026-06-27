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

    /** Default theme returned by {@link #get(String)} for unknown / null names. */
    public static final Theme AQUA = new Theme(
        "aqua", "#55FFFF", "#00AAAA", "#55FF55", "#FF5555", "#FFAA00", "#AAAAAA");

    /** Cool blue palette. */
    public static final Theme BLUE = new Theme(
        "blue", "#5555FF", "#0000AA", "#55FF55", "#FF5555", "#FFAA00", "#AAAAAA");

    /** Warm gold palette. */
    public static final Theme GOLD = new Theme(
        "gold", "#FFAA00", "#FFFF55", "#55FF55", "#FF5555", "#FFAA00", "#AAAAAA");

    /** Green palette. */
    public static final Theme GREEN = new Theme(
        "green", "#55FF55", "#00AA00", "#FFFF55", "#FF5555", "#FFAA00", "#AAAAAA");

    /** Purple palette. */
    public static final Theme PURPLE = new Theme(
        "purple", "#FF55FF", "#AA00AA", "#55FF55", "#FF5555", "#FFAA00", "#AAAAAA");

    /** Red palette. */
    public static final Theme RED = new Theme(
        "red", "#FF5555", "#AA0000", "#55FF55", "#FFAA00", "#FFFF55", "#AAAAAA");

    /** Neutral white palette. */
    public static final Theme WHITE = new Theme(
        "white", "#FFFFFF", "#AAAAAA", "#55FF55", "#FF5555", "#FFAA00", "#555555");

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
