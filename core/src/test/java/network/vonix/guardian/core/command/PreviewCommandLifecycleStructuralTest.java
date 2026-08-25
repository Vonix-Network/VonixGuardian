package network.vonix.guardian.core.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewCommandLifecycleStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric", "mc-1.18.2/forge",
            "mc-1.19.2/fabric", "mc-1.19.2/forge",
            "mc-1.20.1/fabric", "mc-1.20.1/forge",
            "mc-1.21.1/fabric", "mc-1.21.1/neoforge",
            "mc-26.1/neoforge");

    @Test
    void previewApplyPeeksThenConsumesOnlyInsideAdmittedWorker() throws Exception {
        Path source = Path.of(".");
        if (!Files.isDirectory(source.resolve("mc-1.18.2"))) {
            source = source.resolve("..");
        }
        assertThat(Files.isDirectory(source.resolve("mc-1.18.2"))).isTrue();
        for (String cell : CELLS) {
            Path file = findCommands(source.resolve(cell));
            assertThat(Files.exists(file)).as(cell).isTrue();
            String text = Files.readString(file);
            int start = text.indexOf("public static final class Preview");
            int end = text.indexOf("private static QueryFilter idFilter", start);
            assertThat(start).as(cell).isGreaterThanOrEqualTo(0);
            String preview = text.substring(start, end);
            assertThat(preview).as(cell)
                    .contains("PENDING_PREVIEWS.snapshot(previewKey(actor))")
                    .contains("network.vonix.guardian.core.rollback.RollbackPreviewStore.Snapshot expectedSnapshot = pending.get();")
                    .contains("long expectedGeneration = expectedSnapshot.generation();")
                    .contains("PENDING_PREVIEWS.takeIfSame(\n                        previewKey(actor), expectedGeneration, expectedPreview)")
                    .contains("PermissionNode requiredPreviewPermission")
                    .contains("boolean admitted = submitAsync")
                    .contains("return admitted ? 1 : 0")
                    .doesNotContain("PENDING_PREVIEWS.take(previewKey(actor))");
            String invalidate = "PENDING_PREVIEWS.invalidate(previewKey(actor));";
            assertThat(text).as(cell)
                    .contains("final long previewGeneration = PENDING_PREVIEWS.invalidate(previewKey(actor));")
                    .contains("PENDING_PREVIEWS.putIfGeneration(previewKey(actor), previewGeneration, result);");
            assertThat(text.split(java.util.regex.Pattern.quote(invalidate), -1).length - 1)
                    .as(cell).isEqualTo(2);
        }
    }

    private static Path findCommands(Path cell) throws Exception {
        try (var files = Files.walk(cell)) {
            return files.filter(p -> p.getFileName().toString().equals("GuardianCommands.java"))
                    .findFirst().orElseThrow();
        }
    }
}
