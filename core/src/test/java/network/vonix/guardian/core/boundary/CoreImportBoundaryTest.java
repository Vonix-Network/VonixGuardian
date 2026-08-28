package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class CoreImportBoundaryTest {

    private static final String[] CORE_FORBIDDEN = {
            "net.minecraft.",
            "net.fabricmc.",
            "net.minecraftforge.",
            "net.neoforged.",
            "dev.architectury."
    };

    private static final String[] COMMON_LOADER_FORBIDDEN = {
            "net.fabricmc.",
            "net.minecraftforge.",
            "net.neoforged.",
            "dev.architectury."
    };

    @Test
    void coreMainSourcesHaveNoMinecraftLoaderOrArchitecturyImports() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        List<String> hits = importHits(root.resolve("core/src/main/java"), CORE_FORBIDDEN);
        if (!hits.isEmpty()) {
            fail("core imported a forbidden package:\n" + String.join("\n", hits));
        }
    }

    @Test
    void inLoaderCommonPackagesHaveNoLoaderApiImports() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Path[] commons = {
                root.resolve("mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common"),
                root.resolve("mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common")
        };
        List<String> hits = new ArrayList<>();
        for (Path common : commons) {
            assertTrue(Files.isDirectory(common), "missing common package " + common);
            hits.addAll(importHits(common, COMMON_LOADER_FORBIDDEN));
        }
        if (!hits.isEmpty()) {
            fail("version-common imported a loader API:\n" + String.join("\n", hits));
        }
    }

    public static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/fabric"))) {
                return p;
            }
        }
        return null;
    }

    static List<String> importHits(Path root, String[] prefixes) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    int lineNo = 0;
                    for (String line : Files.readAllLines(file)) {
                        lineNo++;
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("import ")) {
                            continue;
                        }
                        for (String prefix : prefixes) {
                            if (trimmed.contains(prefix)) {
                                hits.add(root.relativize(file) + ":" + lineNo + " " + trimmed);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return hits;
    }
}
