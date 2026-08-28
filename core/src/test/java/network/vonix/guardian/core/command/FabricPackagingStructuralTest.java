package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Pins the four Fabric cells to Loom JarInJar packaging and its artifact guard. */
class FabricPackagingStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric/build.gradle",
            "mc-1.19.2/fabric/build.gradle",
            "mc-1.20.1/fabric/build.gradle",
            "mc-1.21.1/fabric/build.gradle"
    );

    @Test
    void everyFabricCellUsesNestedJdbcArtifacts() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);

            assertThat(text).as(cell)
                    .doesNotContain("include modImplementation(project(':core'))")
                    .contains("tasks.register('embedCoreJarInJar')")
                    .contains("dependsOn ':core:jar', 'remapJar'")
                    .contains("META-INF/jars/${coreJar.name}")
                    .contains("META-INF/jars/core-${project.version}.jar")
                    .contains("include modImplementation('org.xerial:sqlite-jdbc:3.46.1.0')")
                    .contains("include modImplementation('com.mysql:mysql-connector-j:8.4.0')")
                    .contains("include modImplementation('org.postgresql:postgresql:42.7.4')")
                    .contains("include(modImplementation('com.zaxxer:HikariCP:5.1.0'))")
                    .contains("include modImplementation('com.google.code.gson:gson:2.10.1')")
                    .contains("tasks.register('verifyJarInJarPackaging')")
                    .contains("META-INF/jars/*.jar")
                    .contains("def outerJdbcClasses")
                    .contains("META-INF/services/java.sql.Driver")
                    .doesNotContain("com.gradleup.shadow")
                    .doesNotContain("shadowJar")
                    .doesNotContain("archiveClassifier")
                    .doesNotContain("relocate 'org.sqlite'");
            if (cell.startsWith("mc-1.21.1/")) {
                assertThat(text).as(cell + " nested-core metadata")
                        .contains("\"id\": \"vonixguardian-core\"")
                        .contains("coreWithFabricMetadata")
                        .contains("fabric.mod.json")
                        .contains("srcDir project(':mc-1.21.1:common').file('src/main/java')")
                        .contains("network/vonix/guardian/mc/v1_21_1/fabric/api/LocationalInventory.class")
                        .contains("network/vonix/guardian/mc/v1_21_1/fabric/mixin/LocationalInventory.class");
            }
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
}
