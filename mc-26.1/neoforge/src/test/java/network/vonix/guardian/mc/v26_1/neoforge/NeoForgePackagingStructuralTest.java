package network.vonix.guardian.mc.v26_1.neoforge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source-bound guardrails for the 26.1.2 NeoForge release cell.
 *
 * <p>Archive-level and runtime checks remain parent-owned release gates; this
 * test prevents the target's source/resource contract from silently drifting
 * while those gates are run in CI or from another checkout.</p>
 */
final class NeoForgePackagingStructuralTest {

    private static final Pattern MIXIN = Pattern.compile("\\\"([A-Za-z0-9]+Mixin)\\\"");

    private static Path moduleRoot() {
        Path working = Path.of(System.getProperty("user.dir"));
        return Files.isDirectory(working.resolve("src/main"))
            ? working
            : working.resolve("mc-26.1/neoforge");
    }

    @Test
    void target_metadata_and_mixin_classes_are_closed() throws Exception {
        Path root = moduleRoot();
        String toml = Files.readString(root.resolve("src/main/resources/META-INF/neoforge.mods.toml"));
        String mixins = Files.readString(root.resolve("src/main/resources/vg-neoforge.mixins.json"));

        assertThat(toml).contains("versionRange = \"[26.1.2,26.2)\"")
            .contains("versionRange = \"[26.1.2.93,)\"")
            .contains("side = \"SERVER\"");
        assertThat(mixins).contains("\"required\": true")
            .contains("\"defaultRequire\": 0")
            .contains("\"refmap\": \"vg-neoforge.refmap.json\"");
        assertThat(Files.exists(root.resolve("src/main/resources/vg-neoforge.refmap.json")))
            .as("declared mixin refmap is present in resources").isTrue();
        assertThat(Files.readString(root.resolve("src/main/resources/vg-neoforge.refmap.json")))
            .contains("named:named");

        Matcher matcher = MIXIN.matcher(mixins);
        int count = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            assertThat(Files.exists(root.resolve("src/main/java/network/vonix/guardian/mc/v26_1/neoforge/mixin/" + name + ".java")))
                .as("mixin source %s", name).isTrue();
            count++;
        }
        assertThat(count).isEqualTo(20);
    }

    @Test
    void build_and_bootstrap_keep_all_database_drivers_bound() throws Exception {
        Path root = moduleRoot();
        String build = Files.readString(root.resolve("build.gradle"));
        String bootstrap = Files.readString(root.resolve("src/main/java/network/vonix/guardian/mc/v26_1/neoforge/NeoForgeBootstrap.java"));

        assertThat(build).contains("jarJar(libs.sqlite.jdbc)")
            .contains("jarJar(libs.mysql.connector)")
            .contains("jarJar(libs.postgresql)")
            .contains("runtimeOnly libs.sqlite.jdbc")
            .contains("vg-neoforge.refmap.json");
        assertThat(bootstrap).contains("org.sqlite.JDBC")
            .contains("com.mysql.cj.jdbc.Driver")
            .contains("org.postgresql.Driver")
            .contains("ensureDatabaseDriver(config)");
    }
}