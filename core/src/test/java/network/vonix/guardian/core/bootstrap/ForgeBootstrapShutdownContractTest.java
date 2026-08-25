package network.vonix.guardian.core.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ForgeBootstrapShutdownContractTest {

    @Test
    void shutdownLeavesLateGuardianResultsAvailableForStaleCleanup() throws Exception {
        Path source = Path.of("..", "mc-1.18.2", "forge", "src", "main", "java",
                "network", "vonix", "guardian", "mc", "v1_18_2", "forge", "ForgeBootstrap.java")
                .toAbsolutePath().normalize();
        String text = Files.readString(source);

        assertThat(text).doesNotContain("future.cancel(true)");
        assertThat(text).contains("if (!isCurrent(server, generation)) {");
        assertThat(text).contains("closeQuietly(g);");
    }
}
