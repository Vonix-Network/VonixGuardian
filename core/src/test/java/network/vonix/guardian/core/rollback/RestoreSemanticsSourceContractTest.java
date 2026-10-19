package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every loader cell must run the shared clear/place sequence and must not call
 * the entity-creating block-entity read inside those two methods.
 */
class RestoreSemanticsSourceContractTest {

    private static final List<String> LOADER_MUTATORS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/FabricWorldMutator.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/FabricWorldMutator.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricWorldMutator.java",
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricWorldMutator.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeWorldMutator.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeWorldMutator.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeWorldMutator.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/NeoForgeWorldMutator.java",
            "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/NeoForgeWorldMutator.java",
            "mc-26.2/fabric/src/main/java/network/vonix/guardian/mc/v26_2/fabric/FabricWorldMutator.java",
            "mc-26.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_2/neoforge/NeoForgeWorldMutator.java",
            "mc-26.3/fabric/src/main/java/network/vonix/guardian/mc/v26_3/fabric/FabricWorldMutator.java",
            "mc-26.3/neoforge/src/main/java/network/vonix/guardian/mc/v26_3/neoforge/NeoForgeWorldMutator.java");

    @Test
    void everyCellClearsAirBeforeAnyBlockEntityRemoval() throws Exception {
        Path root = repoRoot();
        List<String> problems = new ArrayList<>();
        for (String rel : LOADER_MUTATORS) {
            Path file = root.resolve(rel);
            if (!Files.exists(file)) {
                problems.add(rel + " missing");
                continue;
            }
            String src = Files.readString(file);
            String clear = method(src, "public boolean tryClearBlock(");
            String place = method(src, "private static boolean placeBlock(");
            if (clear == null) problems.add(rel + " has no tryClearBlock");
            if (place == null) problems.add(rel + " has no placeBlock");
            if (clear == null || place == null) continue;
            if (!clear.contains("BlockEntityRestore.clearToAir")) {
                problems.add(rel + " tryClearBlock does not call BlockEntityRestore.clearToAir");
            }
            if (!place.contains("BlockEntityRestore.place")) {
                problems.add(rel + " placeBlock does not call BlockEntityRestore.place");
            }
            if (clear.contains("level.getBlockEntity(")) {
                problems.add(rel + " tryClearBlock calls the entity-creating getBlockEntity");
            }
            if (place.contains("level.getBlockEntity(")) {
                problems.add(rel + " placeBlock calls the entity-creating getBlockEntity");
            }
            int setAt = clear.indexOf("level.setBlock(");
            int removeAt = clear.indexOf("removeBlockEntity(");
            if (setAt < 0 || removeAt < 0 || setAt > removeAt) {
                problems.add(rel + " removeBlockEntity is not after setBlock in tryClearBlock");
            }
            if (!src.contains("EntityCreationType.CHECK")) {
                problems.add(rel + " has no EntityCreationType.CHECK read");
            }
            if (!src.contains("could not clear stale block entity")) {
                problems.add(rel + " dropped the stale-entity failure log");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void containerRemovalPreflightsQuantityBeforeShrink() throws Exception {
        Path root = repoRoot();
        List<String> problems = new ArrayList<>();
        for (String rel : LOADER_MUTATORS) {
            String src = Files.readString(root.resolve(rel));
            String method = method(src,
                    "public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount)");
            if (method == null) {
                problems.add(rel + " has no id-only tryRemoveFromContainer");
                continue;
            }
            int shrink = method.indexOf(".shrink(");
            int preflight = method.indexOf("available < requested");
            if (shrink < 0) {
                problems.add(rel + " id-only remover does not shrink");
            } else if (preflight < 0 || preflight > shrink) {
                problems.add(rel + " shrinks a container stack before the availability check");
            }
        }
        assertThat(problems).isEmpty();
    }

    private static String method(String src, String signature) {
        int sig = src.indexOf(signature);
        if (sig < 0) return null;
        int brace = src.indexOf('{', sig);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(sig, i + 1);
            }
        }
        return null;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))) return p;
        }
        return dir;
    }
}
