package network.vonix.guardian.core.forgeevent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Packaged Forge Mixin rejects non-private unique static helpers. The three
 * Forge hopper mixins must not export {@code vg$snapshot} and must keep the
 * SRG injector bindings that APPLY against the packaged runtime.
 */
class ForgeHopperMixinVisibilityStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/mixin/HopperBlockEntityMixin.java"
    );

    @Test
    void forgeHopperSnapshotHelperIsNotExported() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);
            assertThat(text).as(cell)
                    .doesNotContain("vg$snapshot")
                    .contains("m_155552_(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z")
                    .contains("m_59320_(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;")
                    .contains("ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z")
                    .contains("remap = false")
                    .contains("require = 1")
                    .doesNotContain("method = \"suckInItems\"")
                    .doesNotContain("method = \"tryMoveInItem\"")
                    .doesNotContain("method = \"ejectItems\",")
                    .doesNotContain("require = 0")
                    .doesNotContain("static java.util.Map<Integer, ItemStack> vg$snapshot");
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
}
