package network.vonix.guardian.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class InventoryBridgeNbtStructuralTest {
    @Test
    void allNineBridgesCompareNormalizedNbtAndPersistFullNbt() throws IOException {
        Path source = Path.of(".");
        if (!Files.isDirectory(source.resolve("mc-1.18.2"))) source = source.resolve("..");

        List<Path> bridges;
        try (var stream = Files.walk(source)) {
            bridges = stream
                    .filter(p -> p.getFileName().toString().endsWith("MixinBridge.java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        assertThat(bridges).hasSize(9);

        for (Path bridge : bridges) {
            String text = Files.readString(bridge);
            int start = text.indexOf("public static void playerInventorySlotChange");
            int end = text.indexOf("\n    }\n", start);
            assertThat(start).as(bridge.toString()).isGreaterThanOrEqualTo(0);
            assertThat(end).as(bridge.toString()).isGreaterThan(start);
            String method = text.substring(start, end);

            assertThat(method).as(bridge.toString()).contains("NbtCapture.itemStack(before");
            assertThat(method).as(bridge.toString()).contains("NbtCapture.itemStack(after");
            assertThat(method).as(bridge.toString()).contains("NbtCapture.itemStackComparison(before");
            assertThat(method).as(bridge.toString()).contains("NbtCapture.itemStackComparison(after");
            assertThat(method).as(bridge.toString()).contains("beforeComparisonNbt, afterComparisonNbt");
            assertThat(method).as(bridge.toString()).contains("Integer slot");
            assertThat(method).as(bridge.toString()).contains("submitInventoryReplacement");
            assertThat(method).as(bridge.toString()).contains("InventoryReplacementPairs.isReplacement");
            assertThat(method).as(bridge.toString()).contains(", slot);");
        }
    }

    @Test
    void allNinePlayerMixinsPassKnownOrUnknownSlotExplicitly() throws IOException {
        Path source = Path.of(".");
        if (!Files.isDirectory(source.resolve("mc-1.18.2"))) source = source.resolve("..");
        try (var stream = Files.walk(source)) {
            List<Path> mixins = stream
                    .filter(p -> p.getFileName().toString().equals("PlayerInventoryMixin.java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .sorted().collect(Collectors.toList());
            assertThat(mixins).hasSize(9);
            for (Path mixin : mixins) {
                String text = Files.readString(mixin);
                assertThat(text).as(mixin.toString()).contains("newStack.copy(), i");
                assertThat(text).as(mixin.toString()).doesNotContain("newStack.copy(), null");
            }
        }
    }
}
