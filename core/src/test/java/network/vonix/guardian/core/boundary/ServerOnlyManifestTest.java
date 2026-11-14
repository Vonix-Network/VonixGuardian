package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VonixGuardian is a <b>server-side</b> mod. A vanilla client joins a server
 * running it with nothing installed.
 *
 * <p>Forge and NeoForge do not default to that. Without an explicit
 * {@code displayTest = "IGNORE_ALL_VERSION"} in the mod metadata, the loader
 * applies a version check and every client without the mod is refused at join
 * with a "missing mods" screen. That is not a subtle failure: the server looks
 * broken to every player, and the operator's audit tool is the thing that
 * appears to have caused it.</p>
 *
 * <p>Fabric gets this right through {@code "environment": "server"} in
 * {@code fabric.mod.json}, which is why only the Forge-family manifests are
 * checked here. This test exists because shipping without the key passed every
 * other gate — the jar built, the mixins registered, and the suite was green
 * while the mod was unusable by every unmodded client.</p>
 */
class ServerOnlyManifestTest {

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("mc-1.18.2"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    /** Forge and NeoForge manifests, which both live under META-INF. */
    private static List<Path> forgeFamilyManifests() throws IOException {
        Path root = repoRoot();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (!name.equals("mods.toml") && !name.equals("neoforge.mods.toml")) {
                    continue;
                }
                if (p.toString().contains("/build/")) {
                    continue;
                }
                out.add(p);
            }
        }
        return out;
    }

    /** Fabric manifests, which declare their side differently. */
    private static List<Path> fabricManifests() throws IOException {
        Path root = repoRoot();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.getFileName() != null
                    && p.getFileName().toString().equals("fabric.mod.json")
                    && !p.toString().contains("/build/")) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    @Test
    void everyForgeFamilyManifestIgnoresClientModPresence() throws IOException {
        List<Path> manifests = forgeFamilyManifests();
        assertThat(manifests)
            .as("expected Forge/NeoForge manifests; if this is empty the walk is broken")
            .isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (Path p : manifests) {
            String text = Files.readString(p);
            if (!text.contains("displayTest")) {
                problems.add(p + " has no displayTest, so clients will be required to "
                    + "install a server-side mod");
            } else if (!text.contains("IGNORE_ALL_VERSION")
                    && !text.contains("IGNORE_SERVER_VERSION")) {
                problems.add(p + " sets an unexpected displayTest; expected IGNORE_ALL_VERSION");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyFabricManifestDeclaresTheServerEnvironment() throws IOException {
        List<Path> manifests = fabricManifests();
        assertThat(manifests).isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (Path p : manifests) {
            String text = Files.readString(p);
            if (!text.contains("\"environment\"")
                || !text.contains("\"server\"")) {
                problems.add(p + " does not declare \"environment\": \"server\"");
            }
        }
        assertThat(problems).isEmpty();
    }
}
