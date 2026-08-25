package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Pins Forge JarJar declarations to the exact transitive libraries shipped. */
class ForgeJarJarStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/forge/build.gradle",
            "mc-1.19.2/forge/build.gradle",
            "mc-1.20.1/forge/build.gradle"
    );

    @Test
    void everyForgeCellDeclaresAllShippedJarJarLibraries() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);
            assertThat(text).as(cell)
                    .contains("jarJar(group: 'org.xerial', name: 'sqlite-jdbc'")
                    .contains("jarJar(group: 'com.mysql', name: 'mysql-connector-j'")
                    .contains("jarJar(group: 'org.postgresql', name: 'postgresql'")
                    .contains("jarJar(group: 'org.checkerframework', name: 'checker-qual'")
                    .contains("jarJar(group: 'com.google.protobuf', name: 'protobuf-java'")
                    .contains("if (entry.name.startsWith('META-INF/jarjar/slf4j-api-')) return")
                    .doesNotContain("jarJar(group: 'org.slf4j', name: 'slf4j-api'");
        }
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/forge"))) {
                return p;
            }
        }
        return null;
    }
}
