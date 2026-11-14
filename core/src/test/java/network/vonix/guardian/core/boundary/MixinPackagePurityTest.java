package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mixin forbids any class outside its declared package from referencing a class
 * <em>inside</em> it, and enforces that at class-load time:
 *
 * <pre>
 * IllegalClassLoadError: ...forge.mixin.MixinLoggingSupport is in a defined mixin
 * package ... and cannot be referenced directly
 * </pre>
 *
 * <p>This is not a boot failure. The mod loads, the mixin applies, and the server
 * dies later — on the server thread — the first time an event runs that handler.
 * It crashed a live server this way: thirteen mixins called a shared helper that
 * happened to sit in the mixin package.</p>
 *
 * <p>The rule this test enforces: <b>every class in a declared mixin package must be
 * a registered mixin.</b> A helper class in that directory is illegal by definition,
 * because the only reason to put a class there is to reference it — and referencing
 * it is what throws.</p>
 */
class MixinPackagePurityTest {

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("mc-1.18.2"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    /** A mixin config and the source directory that mirrors its declared package. */
    private record Config(Path file, Path packageDir, Set<String> registered) {
    }

    private static List<Config> mixinConfigs() throws IOException {
        Path root = repoRoot();
        List<Config> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (!name.endsWith(".mixins.json") || p.toString().contains("/build/")) {
                    continue;
                }
                String text = Files.readString(p);
                java.util.regex.Matcher pm =
                    java.util.regex.Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (!pm.find()) {
                    continue;
                }
                String pkg = pm.group(1);
                // Collect every quoted name inside the mixins / client / server arrays.
                Set<String> registered = new LinkedHashSet<>();
                for (String key : new String[] {"mixins", "client", "server"}) {
                    java.util.regex.Matcher km = java.util.regex.Pattern
                        .compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL)
                        .matcher(text);
                    while (km.find()) {
                        java.util.regex.Matcher nm =
                            java.util.regex.Pattern.compile("\"([A-Za-z0-9_$]+)\"").matcher(km.group(1));
                        while (nm.find()) {
                            registered.add(nm.group(1));
                        }
                    }
                }
                // resources/<...>/x.mixins.json  ->  java/<pkg path>
                Path resourcesDir = p.getParent();
                Path javaDir = resourcesDir;
                // walk up until the directory name is "resources"
                while (javaDir != null && javaDir.getFileName() != null
                        && !javaDir.getFileName().toString().equals("resources")) {
                    javaDir = javaDir.getParent();
                }
                if (javaDir == null) {
                    continue;
                }
                Path srcRoot = javaDir.resolveSibling("java");
                Path pkgDir = srcRoot.resolve(pkg.replace('.', '/'));
                if (Files.isDirectory(pkgDir)) {
                    out.add(new Config(p, pkgDir, registered));
                }
            }
        }
        return out;
    }

    @Test
    void everyClassInAMixinPackageIsARegisteredMixin() throws IOException {
        List<Config> configs = mixinConfigs();
        assertThat(configs)
            .as("expected at least one mixin config with a matching source directory; "
                + "an empty list means this guard silently checks nothing")
            .isNotEmpty();

        Map<String, String> problems = new LinkedHashMap<>();
        for (Config c : configs) {
            try (Stream<Path> files = Files.list(c.packageDir())) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String fn = f.getFileName().toString();
                    if (!fn.endsWith(".java")) {
                        continue;
                    }
                    String simple = fn.substring(0, fn.lastIndexOf('.'));
                    if (!c.registered().contains(simple)) {
                        problems.put(
                            c.packageDir().getFileName() + "/" + simple,
                            "in the declared mixin package but not listed in "
                                + c.file().getFileName()
                                + " — anything referencing it throws IllegalClassLoadError "
                                + "at runtime, on the server thread");
                    }
                }
            }
        }

        assertThat(problems)
            .as("a non-mixin helper class inside a mixin package is illegal by design; "
                + "move it to a sibling package such as forge.support")
            .isEmpty();
    }
}
