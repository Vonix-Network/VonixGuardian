package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container rollback must return items, not insist on one slot.
 *
 * <p>Rolling back a withdrawal puts items back into a chest. By the time an operator
 * runs the rollback the chest has usually been used again, so the slot the row recorded
 * now holds something else — or the same item but full. Refusing in that case made a
 * rollback of a busy base fail hundreds of rows at once: a live server logged 257
 * `CONTAINER_WITHDRAW`/`CONTAINER_DEPOSIT` failures and left its batch open.</p>
 *
 * <p>The loader cells implement the container write, so this checks the shipped source
 * for the fallback rather than duplicating Minecraft's Container. A unit test can prove
 * the logic only by stubbing a whole inventory; the property that matters here is that
 * the code has a path which places the stack anywhere it fits.</p>
 */
class ContainerRollbackFallbackTest {

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

    /** Every loader cell's world mutator. */
    private static java.util.List<Path> mutators() throws IOException {
        Path root = repoRoot();
        java.util.List<Path> out = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.toString().contains("/build/")
                        || p.getFileName() == null
                        || !p.getFileName().toString().endsWith("WorldMutator.java")) {
                    continue;
                }
                // The core interface declares the contract and implements nothing;
                // it is not a loader cell and must not carry a fallback.
                if (p.toString().contains("/core/rollback/")) {
                    continue;
                }
                out.add(p);
            }
        }
        return out;
    }

    @Test
    void everyCellHasAnAnywhereFallbackForContainerWrites() throws IOException {
        java.util.List<Path> cells = mutators();
        assertThat(cells)
            .as("expected at least one loader WorldMutator; an empty list means this "
                + "guard checks nothing")
            .isNotEmpty();

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (Path p : cells) {
            String src = Files.readString(p);
            if (!src.contains("tryAddAnywhere")) {
                missing.add(repoRoot().relativize(p).toString());
            }
        }
        assertThat(missing)
            .as("a cell without an anywhere-fallback refuses to return items when the "
                + "recorded slot is occupied, which fails a whole batch at once")
            .isEmpty();
    }

    /**
     * The failure log must name the row's data. 257 identical
     * "mutation unsuccessful for id=..." lines told an operator nothing about whether
     * the container was missing, full, or different.
     */
    @Test
    void theFailureLineCarriesEnoughToDiagnoseIt() throws IOException {
        Path engine = repoRoot()
            .resolve("core/src/main/java/network/vonix/guardian/core/rollback/RollbackEngine.java");
        String src = Files.readString(engine);
        int at = src.indexOf("mutation unsuccessful for action id={} type={}");
        assertThat(at)
            .as("the failure log line moved or was reworded; update this guard")
            .isGreaterThanOrEqualTo(0);
        String tail = src.substring(at, Math.min(src.length(), at + 400));
        assertThat(tail)
            .as("the failure line must include position, target, amount and slot")
            .contains("target={}")
            .contains("amount={}")
            .contains("slot={}");
    }
}
