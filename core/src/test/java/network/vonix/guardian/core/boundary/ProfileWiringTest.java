package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fabric 26.x cells use Loom 1.17.21, which requires Gradle 9.5+. The NeoForge
 * 26.2 and 26.3 jobs run Gradle 9.2.0, so those profiles must not include the
 * Fabric projects.
 */
class ProfileWiringTest {

    @Test
    void fabric26CellsAreOnTheirOwnProfiles() throws Exception {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        String settings = Files.readString(root.resolve("settings.gradle"));

        assertThat(previousCodeLine(settings, "include ':mc-26.2:fabric'"))
                .isEqualTo("if (profile == 'mc262fabric') {");
        assertThat(previousCodeLine(settings, "include ':mc-26.3:fabric'"))
                .isEqualTo("if (profile == 'mc263fabric') {");
        assertThat(settings).doesNotContain("profile == 'mc262fabric' || profile == 'mc262'");
        assertThat(settings).doesNotContain("profile == 'mc263fabric' || profile == 'mc263'");
        assertThat(settings).contains("include ':mc-26.2:neoforge'");
        assertThat(settings).contains("include ':mc-26.3:neoforge'");
        assertThat(previousCodeLine(settings, "include ':mc-26.2:neoforge'"))
                .isEqualTo("if (profile != 'mc263') {");
        assertThat(previousCodeLine(settings, "include ':mc-26.3:neoforge'"))
                .isEqualTo("if (profile != 'mc262') {");
    }

    private static String previousCodeLine(String src, String needle) {
        int at = src.indexOf(needle);
        assertThat(at).isGreaterThan(0);
        int cursor = src.lastIndexOf('\n', at);
        while (cursor > 0) {
            int prev = src.lastIndexOf('\n', cursor - 1);
            String line = src.substring(prev + 1, cursor).trim();
            if (!line.isEmpty() && !line.startsWith("//")) return line;
            cursor = prev;
        }
        return "";
    }
}
