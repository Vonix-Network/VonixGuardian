package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecordTypeAdapterFactoryTest {
    @Test
    void config_loader_round_trips_immutable_records(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("config.json");
        GuardianConfig original = GuardianConfig.defaults();

        ConfigLoader.save(path, original);
        GuardianConfig restored = ConfigLoader.load(path);

        assertThat(restored.database().type()).isEqualTo(original.database().type());
        assertThat(restored.actions().itemBlacklist()).containsExactlyElementsOf(original.actions().itemBlacklist());
        assertThat(restored.rollback()).isEqualTo(original.rollback());
    }

    @Test
    void config_loader_invokes_record_constructor_for_absent_fields(@TempDir Path tmp) throws Exception {
        Path path = tmp.resolve("legacy-config.json");
        GuardianConfig original = GuardianConfig.defaults();
        ConfigLoader.save(path, original);
        String legacy = Files.readString(path, StandardCharsets.UTF_8)
                .replaceFirst("(?s)\\s*\\\"rollback\\\"\\s*:\\s*\\{.*?\\},", "");
        Files.writeString(path, legacy, StandardCharsets.UTF_8);

        GuardianConfig restored = ConfigLoader.load(path);

        assertThat(restored.rollback()).isEqualTo(GuardianConfig.Rollback.defaults());
    }
}
