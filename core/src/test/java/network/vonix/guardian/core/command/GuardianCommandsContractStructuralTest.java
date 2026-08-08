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

            assertThat(text)
                .as("%s should enforce the configured numeric radius in every command path", cell)
                .hasSizeGreaterThan(0)
                .contains("QueryParser.enforceMaxRadius(")
                .contains("g.config().lookup().maxRadius()");
            assertThat(countOccurrences(text, "QueryParser.enforceMaxRadius("))
                .as("%s should enforce radius for lookup, rollback, restore, and purge", cell)
                .isEqualTo(4);
            assertThat(text)
                .as("%s should make #count a count-only lookup", cell)
                .contains("if (filter.countOnly())")
                .contains("[VonixGuardian] Count: ")
                .contains("LookupPermissionFilter.countVisible(")
                .doesNotContain("long total = g.dao().count(filter);");
            assertThat(text)
                .as("%s should fill pages in visible-row coordinates", cell)
                .contains("LookupPermissionFilter.visiblePage(")
                .contains("LookupPermissionFilter.VisiblePage visiblePage =")
                .contains("if (!visiblePage.complete())")
                .contains("visiblePage.rows()")
                .contains("Lookup aborted: the permission-filtered page exceeded")
                .doesNotContain("LookupPermissionFilter.visiblePage(\n                            g.dao(), g.perms(), viewer, PermissionNode.LOOKUP,\n                            filter, pageActual, perPageF).rows()");
            assertThat(text.indexOf("if (filter.countOnly())"))
                .as("%s should branch before the visible lookup query", cell)
                .isLessThan(text.indexOf("LookupPermissionFilter.visiblePage("));
            assertThat(text.indexOf("if (filter.countOnly())"))
                .as("%s should branch before row formatting", cell)
                .isLessThan(text.indexOf("LookupFormatter.page("));

            // Rollback/restore must apply default radius BEFORE withDefaultWorld so an
            // implicit player world does not suppress the required r:10 default.
            String rollbackBlock =
                    "qf = withDefaultRollbackRadius(qf, src);\n"
                            + "                qf = qf.withDefaultWorld(playerWorldOf(src));\n"
                            + "                qf = QueryParser.enforceMaxRadius(qf, g.config().lookup().maxRadius());";
            int firstOrdered = text.indexOf(rollbackBlock);
            assertThat(firstOrdered)
                .as("%s rollback path must order defaultRadius before withDefaultWorld before enforceMaxRadius", cell)
                .isGreaterThan(0);
            int secondOrdered = text.indexOf(rollbackBlock, firstOrdered + 1);
            assertThat(secondOrdered)
                .as("%s restore path must order defaultRadius before withDefaultWorld before enforceMaxRadius", cell)
                .isGreaterThan(firstOrdered);
            // Negative: do not reintroduce world-before-radius ordering on either path.
            assertThat(text)
                .as("%s must not apply withDefaultWorld before withDefaultRollbackRadius", cell)
                .doesNotContain(
                        "qf = qf.withDefaultWorld(playerWorldOf(src));\n"
                                + "                qf = withDefaultRollbackRadius(qf, src);");

            // Explicit region selectors must not be narrowed by r:10 defaults.
            // QueryParser leaves radius null for worldSel / worldEditPlayer paths.
            assertThat(text)
                .as("%s must preserve worldSel/worldEditPlayer when defaulting rollback radius", cell)
                .contains("qf.radius() != null || qf.worldSel() != null || qf.worldEditPlayer() != null");
            assertThat(countOccurrences(text,
                    "if (qf.radius() != null || qf.worldSel() != null || qf.worldEditPlayer() != null)"))
                .as("%s should have exactly one withDefaultRollbackRadius guard", cell)
                .isEqualTo(1);
            // Must not regress to radius-only short-circuit.
            assertThat(text)
                .as("%s must not use radius-only default guard", cell)
                .doesNotContain("if (qf.radius() != null) {\n            return qf;\n        }");
            // withDefaultRollbackRadius body must still apply DEFAULT_ROLLBACK_RADIUS
            // when radius/worldSel/worldEdit are all absent (omitted player radius).
            assertThat(text)
                .as("%s withDefaultRollbackRadius must reference DEFAULT_ROLLBACK_RADIUS", cell)
                .contains("DEFAULT_ROLLBACK_RADIUS");
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
