package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryNbtCaptureStructuralTest {
    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric", "mc-1.18.2/forge",
            "mc-1.19.2/fabric", "mc-1.19.2/forge",
            "mc-1.20.1/fabric", "mc-1.20.1/forge",
            "mc-1.21.1/fabric", "mc-1.21.1/neoforge",
            "mc-26.1.2/neoforge");

    @Test
    void keepsFullRollbackPayloadSeparateFromComparisonNormalization() throws Exception {
        Path source = Path.of(".");
        if (!Files.isDirectory(source.resolve("mc-1.18.2"))) source = source.resolve("..");
        for (String cell : CELLS) {
            Path file;
            try (var files = Files.walk(source.resolve(cell))) {
                file = files.filter(p -> p.getFileName().toString().equals("NbtCapture.java"))
                        .findFirst().orElseThrow();
            }
            String text = Files.readString(file);
            String full = method(text, "static byte[] itemStack(");
            String comparison = method(text, "static byte[] itemStackComparison(");
            assertThat(full).as(cell + " full payload")
                    .contains("return write")
                    .doesNotContain("remove(\"Count\")")
                    .doesNotContain("remove(\"count\")");
            assertThat(comparison).as(cell + " comparison payload")
                    .contains("remove(\"Count\")")
                    .contains("remove(\"count\")")
                    .contains("return write");
        }
    }

    private static String method(String text, String signature) {
        int start = text.indexOf(signature);
        assertThat(start).as("missing " + signature).isGreaterThanOrEqualTo(0);
        int end = text.indexOf("\n    /**", start + signature.length());
        if (end < 0) end = text.indexOf("\n    static byte[] entity", start + signature.length());
        assertThat(end).as("missing method boundary for " + signature).isGreaterThan(start);
        return text.substring(start, end);
    }
}
