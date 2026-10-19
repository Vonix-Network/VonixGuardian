package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionAndConfirmDefaultsTest {

    @Test
    void shippedRetentionStaysUnboundedAndConfirmStaysOff() {
        assertThat(GuardianConfig.DEFAULT_AUTO_PURGE_SECONDS).isZero();
        assertThat(GuardianConfig.SUGGESTED_BUSY_SERVER_AUTO_PURGE_SECONDS).isEqualTo(7_776_000L);
        GuardianConfig defaults = GuardianConfig.defaults();
        assertThat(defaults.purge().autoPurgeSeconds()).isZero();
        assertThat(defaults.rollback().requireConfirm()).isFalse();
        assertThat(defaults.privacy().hashIps()).isFalse();
        assertThat(new GuardianConfig.Rollback(16, 1000).requireConfirm()).isFalse();
    }

    @Test
    void bootWarnsWhenTheLoadedRetentionValueIsZero() throws Exception {
        Path guardian = repoRoot().resolve("core/src/main/java/network/vonix/guardian/core/Guardian.java");
        String src = Files.readString(guardian);
        int boot = src.indexOf("public static Guardian boot(");
        int warn = src.indexOf("autoPurgeSeconds is 0 (unbounded retention");
        assertThat(warn).isGreaterThan(boot);
        assertThat(src).contains("config.purge().autoPurgeSeconds() == 0L");
        assertThat(src).contains("SUGGESTED_BUSY_SERVER_AUTO_PURGE_SECONDS");
    }

    @Test
    void everyCommandCellTreatsConfirmAsOptIn() throws Exception {
        Path root = repoRoot();
        String[] commands = {
                "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
                "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
                "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
                "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
                "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
                "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
                "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java",
                "mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common/GuardianCommands.java",
                "mc-26.2/common/src/main/java/network/vonix/guardian/mc/v26_2/common/GuardianCommands.java",
                "mc-26.3/common/src/main/java/network/vonix/guardian/mc/v26_3/common/GuardianCommands.java"
        };
        for (String rel : commands) {
            String src = Files.readString(root.resolve(rel));
            assertThat(src).contains("previewForced || filter.preview() || rollbackRequiresConfirm(g)");
            assertThat(src).contains("filter.preview() || rollbackRequiresConfirm(g)");
            assertThat(src).contains("c.rollback().requireConfirm()");
        }
    }

    @Test
    void publicDocsMatchShippedPrivacyAndThe220Release() throws Exception {
        Path root = repoRoot();
        String faq = Files.readString(root.resolve("docs/FAQ.md"));
        assertThat(faq).contains("privacy.hashIps: false");
        assertThat(faq).doesNotContain("The default is\n`hashIps: true`");
        assertThat(faq).contains("JSON-lines");
        String changelog = Files.readString(root.resolve("CHANGELOG.md"));
        int start = changelog.indexOf("## [2.2.0]");
        int end = changelog.indexOf("## [2.1.1]");
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String section = changelog.substring(start, end);
        assertThat(section).doesNotContain("2.1.2-beta");
        assertThat(section).doesNotContain("nine-cell");
        String workflow = Files.readString(root.resolve(".github/workflows/release.yml"));
        assertThat(workflow).doesNotContain("schema v8");
        assertThat(workflow).contains("## \\[2\\.2\\.0\\]");
        assertThat(workflow).contains("prerelease: false");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle"))) return p;
        }
        return dir;
    }
}
