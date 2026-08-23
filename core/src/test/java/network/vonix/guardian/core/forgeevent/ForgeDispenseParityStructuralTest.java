package network.vonix.guardian.core.forgeevent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structural gate: the three Forge cells wire DISPENSE through
 * {@code DispenserBlockMixin} + {@code vg.mixins.json} + MANIFEST MixinConfigs,
 * matching the Fabric/NeoForge submitDispense contract.
 */
class ForgeDispenseParityStructuralTest {

    private static final List<Cell> CELLS = List.of(
            new Cell("mc-1.18.2/forge", "v1_18_2"),
            new Cell("mc-1.19.2/forge", "v1_19_2"),
            new Cell("mc-1.20.1/forge", "v1_20_1")
    );

    @Test
    void everyForgeCellWiresDispenseMixinAndRefmap() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (Cell cell : CELLS) {
            Path mixin = root.resolve(cell.module + "/src/main/java/network/vonix/guardian/mc/"
                    + cell.pkg + "/forge/mixin/DispenserBlockMixin.java");
            Path json = root.resolve(cell.module + "/src/main/resources/vg.mixins.json");
            Path refmap = root.resolve(cell.module + "/src/main/resources/vg.refmap.json");
            Path bridge = root.resolve(cell.module + "/src/main/java/network/vonix/guardian/mc/"
                    + cell.pkg + "/forge/ForgeMixinBridge.java");
            Path build = root.resolve(cell.module + "/build.gradle");
            Path events = root.resolve(cell.module + "/src/main/java/network/vonix/guardian/mc/"
                    + cell.pkg + "/forge/ForgeEvents.java");

            assertThat(Files.exists(mixin)).as(cell.module + " DispenserBlockMixin").isTrue();
            String mixinSrc = Files.readString(mixin);
            assertThat(mixinSrc)
                    .contains("@Mixin(DispenserBlock.class)")
                    .contains("method = \"dispenseFrom\"")
                    .contains("ForgeMixinBridge.dispense");
            assertThat(mixinSrc).contains("ServerLevel level, BlockPos pos");

            String mixinsJson = Files.readString(json);
            assertThat(mixinsJson)
                    .contains("\"DispenserBlockMixin\"")
                    .contains("\"refmap\": \"vg.refmap.json\"")
                    .contains("\"package\": \"network.vonix.guardian.mc." + cell.pkg + ".forge.mixin\"");

            String refmapJson = Files.readString(refmap);
            assertThat(refmapJson).contains("m_5824_");

            String bridgeSrc = Files.readString(bridge);
            assertThat(bridgeSrc)
                    .contains("public static void dispense(")
                    .contains("s.submitDispense(null, \"#dispenser\"");

            String gradle = Files.readString(build);
            assertThat(gradle).contains("'MixinConfigs': 'vg.mixins.json'");
            assertThat(gradle).contains("vg.mixins.json");
            assertThat(gradle).contains("vg.refmap.json");

            String eventsSrc = Files.readString(events);
            assertThat(eventsSrc).doesNotContain("ACKNOWLEDGED GAP: DISPENSE is not covered");
        }
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/forge"))) {
                return p;
            }
        }
        return null;
    }

    private record Cell(String module, String pkg) {}
}
