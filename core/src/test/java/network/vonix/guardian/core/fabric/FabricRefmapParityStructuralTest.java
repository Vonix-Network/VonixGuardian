package network.vonix.guardian.core.fabric;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structural gate for the Fabric Mixin refmap contract. Fabric Loom emits a
 * cell-specific refmap name; every source config must name that packaged file.
 */
class FabricRefmapParityStructuralTest {

    private static final List<Cell> CELLS = List.of(
            new Cell("mc-1.18.2/fabric", "1.18.2"),
            new Cell("mc-1.19.2/fabric", "1.19.2"),
            new Cell("mc-1.20.1/fabric", "1.20.1"),
            new Cell("mc-1.21.1/fabric", "1.21.1")
    );

    @Test
    void everyFabricCellNamesItsGeneratedRefmap() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (Cell cell : CELLS) {
            Path json = root.resolve(cell.module + "/src/main/resources/vg.mixins.json");
            assertThat(Files.exists(json)).as(cell.module + " mixin config").isTrue();

            String expected = "vonixguardian-fabric-" + cell.version + "-refmap.json";
            String mixinsJson = Files.readString(json);
            assertThat(mixinsJson)
                    .contains("\"refmap\": \"" + expected + "\"")
                    .doesNotContain("\"refmap\": \"vg.refmap.json\"");
        }
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/fabric"))) {
                return p;
            }
        }
        return null;
    }

    private record Cell(String module, String version) {}
}
