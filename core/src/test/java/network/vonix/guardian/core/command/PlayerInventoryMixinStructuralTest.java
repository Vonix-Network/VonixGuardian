package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Keeps inventory producers independent of unmapped @Shadow contracts. */
class PlayerInventoryMixinStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/mixin/PlayerInventoryMixin.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/PlayerInventoryMixin.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/mixin/PlayerInventoryMixin.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/mixin/PlayerInventoryMixin.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/mixin/PlayerInventoryMixin.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/mixin/PlayerInventoryMixin.java",
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/mixin/PlayerInventoryMixin.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/mixin/PlayerInventoryMixin.java",
            "mc-26.1/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/mixin/PlayerInventoryMixin.java"
    );

    @Test
    void everyCellUsesDirectInventoryApiWithoutShadowedMethods() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);
            assertThat(text).as(cell)
                    .contains("Inventory inventory = (Inventory) (Object) this;")
                    .contains("Player owner = inventory.player;")
                    .contains("inventory.getItem(slot).copy()")
                    .contains("inventory.getContainerSize()")
                    .contains("require = 1")
                    .contains("cancellable = true")
                    .contains("vg$inWrappedCall")
                    .contains("vg$abortSnapshot")
                    .contains("vg$beginSnapshot")
                    .contains("vg$finishSnapshot")
                    .contains("vg$runSetItem")
                    .contains("inventory.setItem(slot, after)")
                    .contains("vg$beforePredicateClear")
                    .contains("inventoryMetadataChanged")
                    .doesNotContain("@Shadow")
                    .doesNotContain("abstract ItemStack getItem")
                    .doesNotContain("@At(\"RETURN\")");
            if (cell.contains("/forge/")) {
                assertThat(text).as(cell)
                        .contains("m_6836_(ILnet/minecraft/world/item/ItemStack;)V")
                        .contains("m_36054_(Lnet/minecraft/world/item/ItemStack;)Z")
                        .contains("m_36057_(Lnet/minecraft/world/item/ItemStack;)V")
                        .contains("m_36022_(Ljava/util/function/Predicate;ILnet/minecraft/world/Container;)I")
                        .contains("remap = false");
            } else {
                assertThat(text).as(cell)
                        .contains("clearOrCountMatchingItems(Ljava/util/function/Predicate;ILnet/minecraft/world/Container;)I")
                        .contains("setItem(ILnet/minecraft/world/item/ItemStack;)V")
                        .contains("add(Lnet/minecraft/world/item/ItemStack;)Z")
                        .contains("removeItem(Lnet/minecraft/world/item/ItemStack;)V");
            }
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
