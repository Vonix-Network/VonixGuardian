package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every Hopper Mixin must export only private static injection handlers.
 * Ordinary helper methods are applied to the Minecraft target class; a
 * package-private helper caused Fabric Loader 0.19.3 to reject the mod before
 * server readiness.
 */
class HopperMixinVisibilityStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/mixin/HopperBlockEntityMixin.java",
            "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/mixin/HopperBlockEntityMixin.java"
    );

    private static final Pattern NON_PRIVATE_STATIC_METHOD = Pattern.compile(
            "(?m)^\\s*(?!private\\s+)(?:(?:public|protected)\\s+)?static\\s+[^;{\\n]+\\([^;{\\n]*\\)\\s*(?:throws [^{\\n]+)?\\{");

    @Test
    void everyHopperMixinHasNoExportedNormalHelper() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);
            assertThat(text).as(cell)
                    .doesNotContain("vg$snapshot")
                    .doesNotContainPattern(NON_PRIVATE_STATIC_METHOD);
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
