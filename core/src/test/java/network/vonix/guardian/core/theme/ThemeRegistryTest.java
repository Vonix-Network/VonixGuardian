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
