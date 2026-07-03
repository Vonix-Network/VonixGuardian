package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v1.3.1 X9 — regression test that BaseContainerBlockEntityMixin is registered
 * in every fabric cell's {@code vg.mixins.json}. This is what the mixin
 * transformer walks at boot to decide which mixins to apply; a mixin class
 * that exists on disk but is not registered here is a silent no-op.
 *
 * <p>Skipped when the repo root can't be resolved (e.g. running only
 * {@code :core:test} against pre-packaged sources without the parent repo).</p>
 */
class FabricContainerMixinRegistrationTest {

    private static final String[] FABRIC_CELLS = {
        "mc-1.18.2/fabric",
        "mc-1.19.2/fabric",
        "mc-1.20.1/fabric",
        "mc-1.21.1/fabric",
    };

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir"));
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/fabric"))) {
                return p;
            }
        }
        return null;
    }

    @Test
    void everyFabricCell_listsBaseContainerAndContainerMixin() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable — skipping (probably running against packaged core sources)");
        for (String cell : FABRIC_CELLS) {
            Path json = root.resolve(cell + "/src/main/resources/vg.mixins.json");
            assumeTrue(Files.exists(json), "no vg.mixins.json in " + cell);
            String text = Files.readString(json);
            assertThat(text)
                    .as("vg.mixins.json in %s should register ContainerMixin", cell)
                    .contains("\"ContainerMixin\"");
            assertThat(text)
                    .as("vg.mixins.json in %s must register BaseContainerBlockEntityMixin (X9 Ledger-parity)", cell)
                    .contains("\"BaseContainerBlockEntityMixin\"");
        }
    }

    @Test
    void everyFabricCell_hasBaseContainerBlockEntityMixinSource() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : FABRIC_CELLS) {
            String ver = cell.startsWith("mc-1.18.2") ? "v1_18_2"
                       : cell.startsWith("mc-1.19.2") ? "v1_19_2"
                       : cell.startsWith("mc-1.20.1") ? "v1_20_1"
                       : "v1_21_1";
            Path src = root.resolve(cell + "/src/main/java/network/vonix/guardian/mc/" + ver
                    + "/fabric/mixin/BaseContainerBlockEntityMixin.java");
            Path loc = root.resolve(cell + "/src/main/java/network/vonix/guardian/mc/" + ver
                    + "/fabric/mixin/LocationalInventory.java");
            assumeTrue(Files.exists(root.resolve(cell + "/src/main/java")), "cell not present");
            assertThat(src).exists();
            assertThat(loc).exists();
            String bce = Files.readString(src);
            assertThat(bce).contains("@Mixin(BaseContainerBlockEntity.class)");
            assertThat(bce).contains("implements LocationalInventory");
        }
    }

    @Test
    void everyFabricCell_containerMixinWidenedBeyondChestOnly() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : FABRIC_CELLS) {
            String ver = cell.startsWith("mc-1.18.2") ? "v1_18_2"
                       : cell.startsWith("mc-1.19.2") ? "v1_19_2"
                       : cell.startsWith("mc-1.20.1") ? "v1_20_1"
                       : "v1_21_1";
            Path src = root.resolve(cell + "/src/main/java/network/vonix/guardian/mc/" + ver
                    + "/fabric/mixin/ContainerMixin.java");
            assumeTrue(Files.exists(src), "no ContainerMixin.java in " + cell);
            String text = Files.readString(src);
            // Post-X9: must target Barrel and Shulker in addition to Chest.
            assertThat(text)
                    .as("ContainerMixin in %s must target BarrelBlockEntity (X9 widen)", cell)
                    .contains("BarrelBlockEntity.class");
            assertThat(text)
                    .as("ContainerMixin in %s must target ShulkerBoxBlockEntity (X9 widen)", cell)
                    .contains("ShulkerBoxBlockEntity.class");
            assertThat(text)
                    .as("ContainerMixin in %s must still target ChestBlockEntity", cell)
                    .contains("ChestBlockEntity.class");
        }
    }
}
