package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeIncludeGraphTest {

    @Test
    void requestedCellsAreInTheIncludeGraph() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        String settings = Files.readString(root.resolve("settings.gradle"));

        assertTrue(settings.contains("include 'core'"));
        assertTrue(settings.contains("include ':mc-1.21.1:neoforge'"));
        assertTrue(settings.contains("include ':mc-1.21.1:fabric'"));
        assertTrue(settings.contains("include ':mc-26.1.2:neoforge'"));
        assertTrue(settings.contains("includeVersionCommonIfPresent"));
        assertTrue(settings.contains("includeVersionCommonIfPresent('1.21.1')"));
        assertTrue(settings.contains("includeVersionCommonIfPresent('26.1.2')"));

        assertTrue(Files.isDirectory(root.resolve("core")));
        assertTrue(Files.isDirectory(root.resolve("mc-1.21.1/fabric")));
        assertTrue(Files.isDirectory(root.resolve("mc-1.21.1/neoforge")));
        assertTrue(Files.isDirectory(root.resolve("mc-26.1.2/neoforge")));
        assertTrue(Files.isRegularFile(root.resolve("mc-1.21.1/fabric/build.gradle")));
        assertTrue(Files.isRegularFile(root.resolve("mc-1.21.1/neoforge/build.gradle")));
        assertTrue(Files.isRegularFile(root.resolve("mc-26.1.2/neoforge/build.gradle")));

        assertTrue(Files.isRegularFile(root.resolve("mc-1.21.1/common/build.gradle")));
        assertTrue(Files.isRegularFile(root.resolve("mc-26.1.2/common/build.gradle")));
        assertTrue(Files.isDirectory(root.resolve(
                "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common")));
        assertTrue(Files.isDirectory(root.resolve(
                "mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common")));

        // Do not invent cells that do not exist in this repository.
        assertFalse(Files.isDirectory(root.resolve("mc-26.1.2/fabric")));
        assertFalse(Files.isDirectory(root.resolve("mc-1.21.1/forge")));
    }

    @Test
    void requestedLoadersConsumeVersionCommonAsCompileInputs() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        String fabric = Files.readString(root.resolve("mc-1.21.1/fabric/build.gradle"));
        String neo1211 = Files.readString(root.resolve("mc-1.21.1/neoforge/build.gradle"));
        String neo2612 = Files.readString(root.resolve("mc-26.1.2/neoforge/build.gradle"));

        assertTrue(fabric.contains("project(':mc-1.21.1:common')"));
        assertTrue(fabric.contains("srcDir project(':mc-1.21.1:common').file('src/main/java')"));
        assertTrue(neo1211.contains("project(':mc-1.21.1:common')"));
        assertTrue(neo1211.contains("srcDir project(':mc-1.21.1:common').file('src/main/java')"));
        assertTrue(neo2612.contains("project(':mc-26.1.2:common')"));
        assertTrue(neo2612.contains("srcDir project(':mc-26.1.2:common').file('src/main/java')"));
    }
}
