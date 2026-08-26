package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorldMutatorCompensationStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/FabricWorldMutator.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeWorldMutator.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/FabricWorldMutator.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeWorldMutator.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricWorldMutator.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeWorldMutator.java",
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricWorldMutator.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/NeoForgeWorldMutator.java",
            "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/NeoForgeWorldMutator.java"
    );

    @Test
    void allNineExactSlotMutatorsSurfaceUncompensatedRestoreFailure() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "missing " + cell);
            String text = Files.readString(path);
            assertThat(text).as(cell)
                    .contains("restoreExactSlotOrThrow")
                    .contains("UncompensatedSlotMutationException")
                    .doesNotContain("try { c.setItem(slot, previous); c.setChanged(); } catch (Throwable ignored) {}");
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
            if (dir == null) {
                return null;
            }
        }
        return null;
    }
}
