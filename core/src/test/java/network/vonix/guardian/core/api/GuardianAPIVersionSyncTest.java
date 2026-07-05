/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards release bumps from drifting between Gradle metadata and the public API. */
class GuardianAPIVersionSyncTest {

    @Test
    void pluginVersion_matches_root_gradle_properties_modVersion() throws Exception {
        Path repoRoot = findRepoRoot();
        Properties gradleProperties = new Properties();
        try (Reader reader = Files.newBufferedReader(repoRoot.resolve("gradle.properties"), StandardCharsets.UTF_8)) {
            gradleProperties.load(reader);
        }

        assertThat(gradleProperties.getProperty("mod_version"))
                .as("gradle.properties#mod_version must stay in sync with GuardianAPI.PLUGIN_VERSION")
                .isEqualTo(GuardianAPI.PLUGIN_VERSION);
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    && Files.isRegularFile(dir.resolve("gradle.properties"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from " + System.getProperty("user.dir"));
    }
}
