package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VersionCommonIdentityTest {

    private static final Pattern COMMAND_LITERAL = Pattern.compile("Commands\\.literal\\(\"([^\"]+)\"\\)");

    private static final Set<String> EXPECTED_1211 = Set.of(
            "ChatRenderer.java",
            "EntitySentinel.java",
            "GuardianCommands.java",
            "GuardianSuggestions.java",
            "Inspector.java",
            "LookupFormatter.java",
            "NbtAttributionScanner.java",
            "SignMetadataExtractor.java",
            "SourceTagger.java",
            "WorldKey.java"
    );

    @Test
    void fabricAndNeoForgeConsumeTheSame1211CommonCompilationUnits() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Path common = root.resolve("mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common");
        assertTrue(Files.isDirectory(common), "mc-1.21.1/common sources missing");
        Set<String> commonFiles = javaNames(common);
        assertEquals(new TreeSet<>(EXPECTED_1211), commonFiles);

        assertFalse(Files.isDirectory(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/common")));
        assertFalse(Files.isDirectory(root.resolve(
                "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/common")));

        String fabricBuild = Files.readString(root.resolve("mc-1.21.1/fabric/build.gradle"));
        String neoBuild = Files.readString(root.resolve("mc-1.21.1/neoforge/build.gradle"));
        assertTrue(fabricBuild.contains("srcDir project(':mc-1.21.1:common').file('src/main/java')"));
        assertTrue(neoBuild.contains("srcDir project(':mc-1.21.1:common').file('src/main/java')"));
    }

    @Test
    void neoForge2612ConsumesItsVersionCommonCompilationUnits() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Path common = root.resolve("mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common");
        assertTrue(Files.isDirectory(common), "mc-26.1.2/common sources missing");
        Set<String> commonFiles = javaNames(common);
        assertEquals(new TreeSet<>(EXPECTED_1211), commonFiles);
        assertFalse(Files.isDirectory(root.resolve(
                "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/common")));
        String neoBuild = Files.readString(root.resolve("mc-26.1.2/neoforge/build.gradle"));
        assertTrue(neoBuild.contains("srcDir project(':mc-26.1.2:common').file('src/main/java')"));
    }

    private static final Set<String> EXPECTED_COMMAND_LITERALS = Set.of(
            "vg", "co", "guardian",
            "inspect", "i", "inspector",
            "lookup", "l", "page",
            "rollback", "rb", "ro", "apply", "cancel",
            "restore", "rs", "re",
            "purge", "near", "undo",
            "consumer", "pause", "resume", "toggle",
            "status", "stats", "version",
            "reload", "help",
            "config", "get", "set", "list",
            "migrate-db",
            "teleport", "tp", "give",
            "entitylog", "add", "remove"
    );

    @Test
    void commandTreeLiteralsMatchAcross1211Loaders() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Path sharedPath = root.resolve(
                "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java");
        String shared = Files.readString(sharedPath);
        assertTrue(shared.contains("package network.vonix.guardian.mc.v1_21_1.common;"));
        Set<String> literals = literals(shared);
        assertEquals(new TreeSet<>(EXPECTED_COMMAND_LITERALS), literals,
                "1.21.1 common command tree must keep the shared literal set");
        String sentinel = Files.readString(root.resolve(
                "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common/EntitySentinel.java"));
        assertTrue(sentinel.contains("import java.lang.invoke.MethodHandle;"),
                "1.21.1 common EntitySentinel keeps the MethodHandle getType adapter for both loaders");
    }

    @Test
    void commandTreeLiteralsAreSharedWith2612Common() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Set<String> v1211 = literals(Files.readString(root.resolve(
                "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java")));
        Set<String> v2612 = literals(Files.readString(root.resolve(
                "mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common/GuardianCommands.java")));
        assertEquals(v1211, v2612, "command-tree literals must match across requested version commons");
    }

    private static Set<String> javaNames(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> literals(String source) {
        Matcher matcher = COMMAND_LITERAL.matcher(source);
        Set<String> names = new TreeSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
