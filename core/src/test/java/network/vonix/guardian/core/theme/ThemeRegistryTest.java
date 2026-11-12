package network.vonix.guardian.core.theme;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeRegistryTest {

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final List<String> EXPECTED = List.of(
        "aqua", "blue", "gold", "green", "purple", "red", "white");

    @Test
    void everyExpectedThemeIsRegistered() {
        for (String name : EXPECTED) {
            assertThat(ThemeRegistry.has(name)).as("has(%s)", name).isTrue();
            Theme t = ThemeRegistry.get(name);
            assertThat(t).isNotNull();
            assertThat(t.name()).isEqualTo(name);
        }
        assertThat(ThemeRegistry.all()).hasSize(EXPECTED.size());
    }

    @Test
    void textColoursClearMidGreyChat() {
        // #3C3C3C is the mid chat background the old aqua block id failed
        // against (3.85:1). Body colours have to clear WCAG AA, 4.5:1.
        for (Theme theme : ThemeRegistry.all()) {
            for (String colour : List.of(theme.primary(), theme.secondary(), theme.muted(),
                    theme.error(), theme.warning(), theme.success())) {
                assertThat(contrast(colour, "#3C3C3C"))
                    .as("%s %s against mid-grey chat", theme.name(), colour)
                    .isGreaterThanOrEqualTo(4.5);
            }
        }
        assertThat(contrast("#00AAAA", "#3C3C3C")).isLessThan(4.5);
    }

    private static double contrast(String hex, String background) {
        double lighter = Math.max(luminance(hex), luminance(background));
        double darker = Math.min(luminance(hex), luminance(background));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double r = channel((rgb >> 16) & 0xFF);
        double g = channel((rgb >> 8) & 0xFF);
        double b = channel(rgb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    @Test
    void everyThemeHasNonBlankHexColours() {
        for (Theme t : ThemeRegistry.all()) {
            assertThat(t.name()).isNotBlank();
            List<String> colours = List.of(
                t.primary(), t.secondary(), t.success(),
                t.error(), t.warning(), t.muted());
            for (String c : colours) {
                assertThat(c).as("colour of %s", t.name()).isNotBlank();
                assertThat(HEX.matcher(c).matches())
                    .as("%s -> %s is hex", t.name(), c)
                    .isTrue();
            }
        }
    }

    @Test
    void unknownAndBlankNameFallsBackToAqua() {
        assertThat(ThemeRegistry.get(null)).isSameAs(ThemeRegistry.AQUA);
        assertThat(ThemeRegistry.get("")).isSameAs(ThemeRegistry.AQUA);
        assertThat(ThemeRegistry.get("  ")).isSameAs(ThemeRegistry.AQUA);
        assertThat(ThemeRegistry.get("not-a-real-theme")).isSameAs(ThemeRegistry.AQUA);
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(ThemeRegistry.get("AQUA")).isSameAs(ThemeRegistry.AQUA);
        assertThat(ThemeRegistry.get("Gold")).isSameAs(ThemeRegistry.GOLD);
    }

    @Test
    void blankFieldsRejected() {
        assertThatThrownBy(() -> new Theme("x", "  ", "#000000", "#000000",
                "#000000", "#000000", "#000000"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullFieldsRejected() {
        assertThatThrownBy(() -> new Theme(null, "#000000", "#000000", "#000000",
                "#000000", "#000000", "#000000"))
            .isInstanceOf(NullPointerException.class);
    }
}
