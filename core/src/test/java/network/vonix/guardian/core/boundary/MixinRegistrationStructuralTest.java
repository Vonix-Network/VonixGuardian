package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Closed loop between a mixin config and the mixin classes on disk.
 *
 * <p>A mixin is only applied when it is named in the config the loader reads.
 * The failure mode this class exists to catch is silence: a mixin class that
 * exists but is not registered never runs and never errors, and a config entry
 * with no backing class makes mixin fail at apply time with an
 * {@code InvalidMixinException} the operator sees only as a boot crash. Neither
 * is caught by compiling the cell.</p>
 *
 * <p>Nothing here pins source text of a mixin. The assertions are:</p>
 * <ol>
 *   <li>every config entry resolves to a file in the config's own package;</li>
 *   <li>every {@code .java} in that package is registered (no dormant mixin),
 *       and a registration that lives in {@code mixins}/{@code server}/
 *       {@code client} is still resolved through the same lookup;</li>
 *   <li>the class the config names actually declares {@code @Mixin};</li>
 *   <li>the cell's loader metadata actually names the config file, so the
 *       transformer reads it at all — {@code fabric.mod.json},
 *       {@code neoforge.mods.toml}, or the {@code MixinConfigs} manifest
 *       attribute that Forge's service uses.</li>
 * </ol>
 */
class MixinRegistrationStructuralTest {

    /**
     * One loader cell: its mixin config, the metadata file that must name it,
     * and — for Forge only — the build script whose jar manifest carries the
     * {@code MixinConfigs} attribute.
     */
    private record Cell(String name, String resourcesDir, String config, String metadata,
                        String buildScript) {
        Cell(String name, String resourcesDir, String config, String metadata) {
            this(name, resourcesDir, config, metadata, null);
        }
    }

    private static final String FABRIC_META = "fabric.mod.json";
    private static final String NEOFORGE_META = "META-INF/neoforge.mods.toml";
    private static final String FORGE_META = "META-INF/mods.toml";

    /**
     * The {@code MixinConfigs} jar manifest attribute. Groovy accepts both
     * {@code attributes 'MixinConfigs': 'vg.mixins.json'} and the parenthesised
     * form; either puts the same key/value in the manifest.
     */
    private static final Pattern MANIFEST_MIXIN_CONFIG = Pattern.compile(
            "MixinConfigs['\"]?\\s*:\\s*['\"]vg\\.mixins\\.json['\"]");

    /** Every mixin config in the tree, with how its loader is told to read it. */
    private static final List<Cell> CELLS = List.of(
            new Cell("1.18.2-fabric",  "mc-1.18.2/fabric/src/main/resources",   "vg.mixins.json",          FABRIC_META),
            new Cell("1.18.2-forge",   "mc-1.18.2/forge/src/main/resources",    "vg.mixins.json",          FORGE_META,
                    "mc-1.18.2/forge/build.gradle"),
            new Cell("1.19.2-fabric",  "mc-1.19.2/fabric/src/main/resources",   "vg.mixins.json",          FABRIC_META),
            new Cell("1.19.2-forge",   "mc-1.19.2/forge/src/main/resources",    "vg.mixins.json",          FORGE_META,
                    "mc-1.19.2/forge/build.gradle"),
            new Cell("1.20.1-fabric",  "mc-1.20.1/fabric/src/main/resources",   "vg.mixins.json",          FABRIC_META),
            new Cell("1.20.1-forge",   "mc-1.20.1/forge/src/main/resources",    "vg.mixins.json",          FORGE_META,
                    "mc-1.20.1/forge/build.gradle"),
            new Cell("1.21.1-fabric",  "mc-1.21.1/fabric/src/main/resources",   "vg.mixins.json",          FABRIC_META),
            new Cell("1.21.1-neoforge","mc-1.21.1/neoforge/src/main/resources", "vg-neoforge.mixins.json", NEOFORGE_META),
            new Cell("26.1.2-neoforge","mc-26.1.2/neoforge/src/main/resources", "vg-neoforge.mixins.json", NEOFORGE_META),
            new Cell("26.2-fabric",    "mc-26.2/fabric/src/main/resources",     "vg.mixins.json",          FABRIC_META),
            new Cell("26.2-neoforge",  "mc-26.2/neoforge/src/main/resources",   "vg-neoforge.mixins.json", NEOFORGE_META),
            new Cell("26.3-fabric",    "mc-26.3/fabric/src/main/resources",     "vg.mixins.json",          FABRIC_META),
            new Cell("26.3-neoforge",  "mc-26.3/neoforge/src/main/resources",   "vg-neoforge.mixins.json", NEOFORGE_META)
    );

    /** A {@code @Mixin} declaration, used only to prove the class is a mixin at all. */
    private static final Pattern MIXIN_ANNOTATION = Pattern.compile("@Mixin\\s*\\(");

    /** Names of every file in {@code pkgDir} that declares {@code @Mixin}. */
    private static Set<String> mixinClasses(Path pkgDir) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(pkgDir)) {
            List<Path> java = files
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path file : java) {
                if (!MIXIN_ANNOTATION.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    continue; // a helper, not a mixin: never registered by design
                }
                String name = file.getFileName().toString();
                names.add(name.substring(0, name.length() - ".java".length()));
            }
        }
        return names;
    }

    @Test
    void everyConfigExistsForEveryCell() {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        List<String> missing = new ArrayList<>();
        for (Cell cell : CELLS) {
            Path cfg = root.resolve(cell.resourcesDir()).resolve(cell.config());
            if (!Files.isRegularFile(cfg)) {
                missing.add(cell.name() + " -> " + cell.config());
            }
        }
        assertTrue(missing.isEmpty(), "mixin config missing for cell(s): " + missing);
    }

    @Test
    void everyRegisteredMixinResolvesToAClassInTheConfigPackage() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (Cell cell : CELLS) {
            Path cfgPath = root.resolve(cell.resourcesDir()).resolve(cell.config());
            if (!Files.isRegularFile(cfgPath)) {
                continue; // reported by everyConfigExistsForEveryCell
            }
            Map<String, Object> cfg = parseFlatConfig(cfgPath);
            Object pkgValue = cfg.get("package");
            if (!(pkgValue instanceof String pkg) || pkg.isBlank()) {
                problems.add(cell.name() + " : config has no \"package\"");
                continue;
            }
            Path pkgDir = mainJavaDir(root, cell)
                    .resolve(pkg.replace('.', '/'))
                    .normalize();
            if (!Files.isDirectory(pkgDir)) {
                problems.add(cell.name() + " : package dir missing " + root.relativize(pkgDir));
                continue;
            }
            for (String entry : stringList(cfg, "mixins")) {
                Path java = pkgDir.resolve(entry.replace('.', '/') + ".java");
                if (!Files.isRegularFile(java)) {
                    problems.add(cell.name() + " : registered \"" + entry
                            + "\" but no file at " + root.relativize(java));
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("registered mixin(s) with no backing class:\n" + String.join("\n", problems));
        }
    }

    @Test
    void everyMixinClassInThePackageIsRegistered() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> dormant = new ArrayList<>();
        for (Cell cell : CELLS) {
            Path cfgPath = root.resolve(cell.resourcesDir()).resolve(cell.config());
            if (!Files.isRegularFile(cfgPath)) {
                continue;
            }
            Map<String, Object> cfg = parseFlatConfig(cfgPath);
            Object pkgValue = cfg.get("package");
            if (!(pkgValue instanceof String pkg) || pkg.isBlank()) {
                continue;
            }
            Path pkgDir = mainJavaDir(root, cell)
                    .resolve(pkg.replace('.', '/')).normalize();
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }
            // All three arrays feed the transformer: "mixins" the common list,
            // "server"/"client" the side-gated ones. A class in any of them is
            // live; one in none of them never loads.
            Set<String> registered = new TreeSet<>();
            registered.addAll(stringList(cfg, "mixins"));
            registered.addAll(stringList(cfg, "server"));
            registered.addAll(stringList(cfg, "client"));

            for (String name : mixinClasses(pkgDir)) {
                if (!registered.contains(name)) {
                    dormant.add(cell.name() + " : " + name
                            + " declares @Mixin but no config lists it, so it never applies");
                }
            }
        }
        if (!dormant.isEmpty()) {
            fail("mixin class(es) exist but never load because no config names them:\n"
                    + String.join("\n", dormant));
        }
    }

    @Test
    void everyRegisteredMixinDeclaresTheMixinAnnotation() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (Cell cell : CELLS) {
            Path cfgPath = root.resolve(cell.resourcesDir()).resolve(cell.config());
            if (!Files.isRegularFile(cfgPath)) {
                continue;
            }
            Map<String, Object> cfg = parseFlatConfig(cfgPath);
            Object pkgValue = cfg.get("package");
            if (!(pkgValue instanceof String pkg) || pkg.isBlank()) {
                continue;
            }
            Path pkgDir = mainJavaDir(root, cell)
                    .resolve(pkg.replace('.', '/')).normalize();
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }
            for (String entry : stringList(cfg, "mixins")) {
                Path java = pkgDir.resolve(entry.replace('.', '/') + ".java");
                if (!Files.isRegularFile(java)) {
                    continue; // reported by the resolution test
                }
                String text = Files.readString(java, StandardCharsets.UTF_8);
                if (!MIXIN_ANNOTATION.matcher(text).find()) {
                    problems.add(cell.name() + " : " + entry + " is registered but has no @Mixin( ... )");
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("registered class(es) that mixin cannot apply:\n" + String.join("\n", problems));
        }
    }

    /**
     * How a loader is told to read a mixin config:
     * <ul>
     *   <li>Fabric — the {@code "mixins"} array in {@code fabric.mod.json}.</li>
     *   <li>NeoForge — a {@code [[mixins]] config = ...} block in
     *       {@code META-INF/neoforge.mods.toml}.</li>
     *   <li>Forge 1.18.2–1.20.1 — the {@code MixinConfigs} jar manifest
     *       attribute set in the build script. {@code mods.toml} has no mixin
     *       section on these versions.</li>
     * </ul>
     * A config file that is packaged but named by none of these is never read,
     * which is the silent failure this test exists to catch.
     */
    @Test
    void everyCellLoaderMetadataMakesItsMixinConfigReachable() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (Cell cell : CELLS) {
            Path cfgPath = root.resolve(cell.resourcesDir()).resolve(cell.config());
            if (!Files.isRegularFile(cfgPath)) {
                continue; // reported by everyConfigExistsForEveryCell
            }
            switch (cell.metadata()) {
                case FABRIC_META, NEOFORGE_META -> {
                    Path meta = root.resolve(cell.resourcesDir()).resolve(cell.metadata());
                    if (!Files.isRegularFile(meta)) {
                        problems.add(cell.name() + " : loader metadata missing " + cell.metadata());
                        continue;
                    }
                    String text = Files.readString(meta, StandardCharsets.UTF_8);
                    if (!text.contains("\"" + cell.config() + "\"")
                            && !text.contains("'" + cell.config() + "'")) {
                        problems.add(cell.name() + " : " + cell.metadata()
                                + " never names " + cell.config()
                                + ", so the transformer will not read it");
                    }
                }
                case FORGE_META -> {
                    // The mods.toml must still exist (the mod will not load
                    // without it), but the mixin config is declared in the jar
                    // manifest attribute instead.
                    Path meta = root.resolve(cell.resourcesDir()).resolve(cell.metadata());
                    if (!Files.isRegularFile(meta)) {
                        problems.add(cell.name() + " : loader metadata missing " + cell.metadata());
                    }
                    Path build = root.resolve(cell.buildScript());
                    if (!Files.isRegularFile(build)) {
                        problems.add(cell.name() + " : build script missing " + cell.buildScript());
                        continue;
                    }
                    if (!MANIFEST_MIXIN_CONFIG.matcher(Files.readString(build, StandardCharsets.UTF_8)).find()) {
                        problems.add(cell.name() + " : build script does not set "
                                + "MixinConfigs=" + cell.config() + ", so the packaged config is "
                                + "never applied");
                    }
                }
                default -> problems.add(cell.name() + " : unknown metadata kind " + cell.metadata());
            }
        }
        if (!problems.isEmpty()) {
            fail("mixin config present but unreachable from the loader:\n"
                    + String.join("\n", problems));
        }
    }

    /**
     * The {@code src/main/java} root that pairs with a cell's
     * {@code src/main/resources}. Deriving it from the resources path (rather
     * than guessing per cell) keeps every cell on one rule, including the
     * version-common modules that only exist when their build profile is on.
     */
    private static Path mainJavaDir(Path root, Cell cell) {
        return root.resolve(cell.resourcesDir())
                .resolveSibling("java")
                .normalize();
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Minimal JSON reader for a mixin config. Only top-level string members and
     * top-level string arrays are needed, so a general parser (and a new test
     * dependency) would be overkill. Comments and trailing commas are tolerated
     * because the configs are hand-edited.
     *
     * @return top-level member name to value; array values are {@code List<String>}
     */
    static Map<String, Object> parseFlatConfig(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String stripped = stripComments(text);
        Map<String, Object> out = new LinkedHashMap<>();

        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"([^\"]*)\"|\\[([^\\]]*)]|true|false|-?\\d+)")
                .matcher(stripped);
        while (m.find()) {
            String key = m.group(1);
            if (m.group(3) != null) {
                out.put(key, m.group(3));
            } else if (m.group(4) != null) {
                List<String> values = new ArrayList<>();
                Matcher inner = Pattern.compile("\"([^\"]*)\"").matcher(m.group(4));
                while (inner.find()) {
                    values.add(inner.group(1));
                }
                out.put(key, values);
            } else {
                out.put(key, m.group(2));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<String> stringList(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
