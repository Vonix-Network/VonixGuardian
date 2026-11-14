package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command surface is duplicated in {@code GuardianCommands} once per loader
 * cell — ten copies today.
 *
 * <p>{@code CommandTree} exists to collapse that, but adopting it is a large
 * refactor of the release-critical path, so the copies remain. Until they are
 * collapsed, the risk is that one cell gains a literal, alias or permission that
 * another lacks: a server running Fabric then accepts a command the Forge build
 * rejects, and the docs describe whichever cell the author happened to open.</p>
 *
 * <p>This test reads every cell and fails when their command literals diverge.
 * It cannot prove the handlers behave identically — only that the same verbs
 * exist everywhere. That is the drift that actually happens, and it is checkable
 * without a live server.</p>
 */
class CommandSurfaceParityTest {

    /** {@code literal("lookup")} in the Brigadier tree build. */
    private static final Pattern LITERAL = Pattern.compile("literal\\(\"([A-Za-z0-9_]+)\"\\)");

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("mc-1.18.2"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
    }

    /** Every {@code GuardianCommands.java} outside build output, keyed by cell. */
    private static Map<String, Path> commandFiles() throws IOException {
        Path root = repoRoot();
        Map<String, Path> found = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String s = p.toString();
                if (!s.endsWith("GuardianCommands.java") || s.contains("/build/")) {
                    continue;
                }
                // mc-1.18.2/forge/src/... -> "mc-1.18.2/forge"
                Path rel = root.relativize(p);
                if (rel.getNameCount() < 4) {
                    continue;
                }
                found.put(rel.getName(0) + "/" + rel.getName(1), p);
            }
        }
        return found;
    }

    private static Set<String> literalsOf(Path file) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = LITERAL.matcher(Files.readString(file));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void everyCellRegistersTheSameCommandLiterals() throws IOException {
        Map<String, Path> cells = commandFiles();
        assertThat(cells)
            .as("expected the duplicated command files to still exist; if this fails the "
                + "duplication was collapsed and this guard should be replaced by a "
                + "CommandTree adoption test")
            .hasSizeGreaterThanOrEqualTo(2);

        Map<String, Set<String>> byCell = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : cells.entrySet()) {
            byCell.put(e.getKey(), literalsOf(e.getValue()));
        }

        // Union of everything any cell knows, then report who is missing what.
        Set<String> union = new TreeSet<>();
        byCell.values().forEach(union::addAll);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : byCell.entrySet()) {
            Set<String> missing = new LinkedHashSet<>(union);
            missing.removeAll(e.getValue());
            Set<String> extra = new LinkedHashSet<>(e.getValue());
            extra.removeAll(union);
            if (!missing.isEmpty()) {
                problems.add(e.getKey() + " is MISSING " + missing);
            }
            if (!extra.isEmpty()) {
                problems.add(e.getKey() + " has EXTRA " + extra);
            }
        }

        assertThat(problems)
            .as("cells disagree about which commands exist — an operator on one loader "
                + "would get a different command set than on another")
            .isEmpty();
    }

    @Test
    void noCellIsEmpty() throws IOException {
        for (Map.Entry<String, Path> e : commandFiles().entrySet()) {
            assertThat(literalsOf(e.getValue()))
                .as("%s registers no command literals; either the file was gutted or "
                    + "the literal pattern no longer matches its style", e.getKey())
                .isNotEmpty();
        }
    }
}
