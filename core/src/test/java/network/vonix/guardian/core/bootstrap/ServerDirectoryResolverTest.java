package network.vonix.guardian.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerDirectoryResolverTest {

    @TempDir Path tmp;

    public static final class SrgNamedServer {
        private final File dir;

        SrgNamedServer(File dir) {
            this.dir = dir;
        }

        public File m_129843_() {
            return dir;
        }
    }

    public static final class MojmapPathServer {
        private final Path dir;

        MojmapPathServer(Path dir) {
            this.dir = dir;
        }

        public Path getServerDirectory() {
            return dir;
        }
    }

    public static final class NoDirectoryServer {
    }

    @Test
    void resolvesSrgNamedFileMethod() {
        File dir = tmp.resolve("srg-root").toFile();
        Path resolved = ServerDirectoryResolver.resolve(new SrgNamedServer(dir), null);
        assertThat(resolved).isEqualByComparingTo(dir.toPath().toAbsolutePath().normalize());
    }

    @Test
    void resolvesMojmapPathMethod() {
        Path dir = tmp.resolve("mojmap-root");
        Path resolved = ServerDirectoryResolver.resolve(new MojmapPathServer(dir), null);
        assertThat(resolved).isEqualByComparingTo(dir.toAbsolutePath().normalize());
    }

    @Test
    void usesLoaderGameDirWhenServerMethodsMissing() {
        Path loader = tmp.resolve("fml-gamedir");
        Path resolved = ServerDirectoryResolver.resolve(new NoDirectoryServer(), loader);
        assertThat(resolved).isEqualByComparingTo(loader.toAbsolutePath().normalize());
    }

    @Test
    void failsClosedWithoutCwdFallback() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        assertThatThrownBy(() -> ServerDirectoryResolver.resolve(new NoDirectoryServer(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing cwd fallback");
        assertThat(ServerDirectoryResolver.tryServerDirectory(new NoDirectoryServer())).isNull();
        assertThat(cwd).isEqualByComparingTo(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void forgeBootstrapsDoNotFallBackToCwd() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        for (String cell : java.util.List.of(
                "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeBootstrap.java",
                "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeBootstrap.java",
                "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeBootstrap.java")) {
            String text = java.nio.file.Files.readString(root.resolve(cell));
            assertThat(text).as(cell)
                    .contains("ServerDirectoryResolver")
                    .contains("FMLPaths.GAMEDIR")
                    .doesNotContain("using cwd")
                    .doesNotContain("Paths.get(\"\")");
        }
    }
}
