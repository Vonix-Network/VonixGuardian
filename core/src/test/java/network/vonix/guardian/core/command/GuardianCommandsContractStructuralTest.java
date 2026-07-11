package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structural pinning tests for the eight mirrored loader-cell GuardianCommands
 * implementations. The command layer is MC-version-specific, so core tests
 * guard the source shape that keeps the shared command contract true.
 */
class GuardianCommandsContractStructuralTest {

    private static final List<String> CELLS = List.of(
        "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
        "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
        "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
        "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
        "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
        "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
        "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java",
        "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java"
    );

    @Test
    void everyCellPreservesCommandContracts() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);

            assertThat(text)
                .as("%s should pass actor UUID into QueryParseContext for r:#we", cell)
                .contains("new QueryParser.QueryParseContext((int) v.x, (int) v.y, (int) v.z, actorUuid(src))");

            assertThat(text)
                .as("%s should use enum PermissionNode gates so per-node op fallbacks and aliases stay wired", cell)
                .doesNotContain("hasPerm(s, \"vonixguardian.command.")
                .contains("hasPerm(s, PermissionNode.BASE, g)")
                .contains("hasPerm(s, PermissionNode.UNDO, g)");

            assertThat(text)
                .as("%s should use a bounded command-worker queue, not Executors.newFixedThreadPool's unbounded LinkedBlockingQueue", cell)
                .doesNotContain("Executors.newFixedThreadPool")
                .contains("new ThreadPoolExecutor(")
                .contains("new ArrayBlockingQueue<>(COMMAND_WORKER_QUEUE_CAPACITY)")
                .contains("private static boolean submitAsync(CommandSourceStack src, Guardian g, Runnable task)");

            assertThat(text)
                .as("%s should not push rollback/restore previews onto UndoStack", cell)
                .contains("if (!result.preview())");
            assertThat(countOccurrences(text, "g.undoStack().push(actor != null ? actor"))
                .as("%s should only have guarded rollback+restore UndoStack pushes", cell)
                .isEqualTo(2);

            assertThat(text)
                .as("%s should undo exact affected IDs, not replay broad originalFilter", cell)
                .contains("idFilter(prev.originalFilter(), prev.affectedIds())")
                .doesNotContain("plan(originalFilter, inverse")
                .doesNotContain("QueryFilter originalFilter = prev.originalFilter()");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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
