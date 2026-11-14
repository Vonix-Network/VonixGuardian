package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the 1.18.2 Forge logging-coverage mixins are wired end to end, and
 * that each one is bound to a target class and method that the cell's compiler
 * can actually resolve.
 *
 * <p>Three failure modes are silent at compile time and fatal at runtime, so
 * each gets its own assertion:</p>
 * <ol>
 *   <li><b>Dormant mixin.</b> A mixin class that no config lists never applies.
 *       Nothing logs, nothing warns. {@link #everyMixinInTheCellIsRegistered()}
 *       closes both directions — every file registered, every entry backed by a
 *       file — so a rename cannot half-land.</li>
 *   <li><b>Unresolvable target class.</b> {@code @Mixin(SomeClass.class)} only
 *       compiles when {@code SomeClass} resolves. If the simple name is not
 *       imported in that file the cell does not compile; if it is imported but
 *       from the wrong package the mixin applies to nothing.
 *       {@link #everyMixinTargetClassResolvesThroughItsOwnImports()}</li>
 *   <li><b>Unpinned injector.</b> An injector without {@code require = 1} that
 *       stops matching (mapping change, upstream refactor) degrades to a no-op
 *       instead of failing. {@link #everyInjectorFailsLoudly()}</li>
 * </ol>
 *
 * <p>Nothing here pins the source text or the body of a handler. The assertions
 * are about resolution and registration: the properties a refactor must keep,
 * not the spelling a refactor is free to change.</p>
 */
class MixinInjectTargetStructuralTest {

    /** The cell this lane owns. */
    private static final String CELL = "mc-1.18.2/forge";

    private static final String PKG = "network.vonix.guardian.mc.v1_18_2.forge.mixin";

    private static final Pattern MIXIN_ANNOTATION =
            Pattern.compile("@Mixin\\s*\\(\\s*([\\w.$]+)\\.class\\s*\\)");

    private static final Pattern IMPORT =
            Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.$]+)\\s*;");

    private static final Pattern INJECT =
            Pattern.compile("@Inject\\s*\\(([^;]*?)\\)\\s*\\n(?:\\s*(?:private|public|protected)[^\\n]*\\n)?",
                    Pattern.DOTALL);

    private static final Pattern METHOD_TARGET =
            Pattern.compile("method\\s*=\\s*\"([^\"]+)\"");

    /** Java's implicitly-available packages: a name here needs no import. */
    private static final List<String> IMPLICIT = List.of("java.lang.", "java.util.");

    @Test
    void everyMixinInTheCellIsRegistered() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        Path cfgPath = root.resolve(CELL).resolve("src/main/resources/vg.mixins.json");
        assertThat(cfgPath).as("mixin config").exists();

        Set<String> registered = registeredNames(cfgPath);
        assertThat(registered).as("config declares no mixins").isNotEmpty();

        Path pkgDir = root.resolve(CELL).resolve("src/main/java").resolve(PKG.replace('.', '/'));
        assertThat(pkgDir).as("mixin package").isDirectory();

        Set<String> onDisk = mixinClassNames(pkgDir);

        // Direction 1: an entry with no class makes mixin throw at apply time.
        Set<String> withoutClass = new TreeSet<>(registered);
        withoutClass.removeAll(onDisk);

        // Direction 2: a class that no entry names never loads, and never warns.
        Set<String> withoutEntry = new TreeSet<>(onDisk);
        withoutEntry.removeAll(registered);

        List<String> problems = new ArrayList<>();
        if (!withoutClass.isEmpty()) {
            problems.add("registered but no backing class: " + withoutClass);
        }
        if (!withoutEntry.isEmpty()) {
            problems.add("declares @Mixin but no config lists it (dormant): " + withoutEntry);
        }
        if (!problems.isEmpty()) {
            fail("mixin config and mixin package disagree for " + CELL + ":\n"
                    + String.join("\n", problems));
        }

        // The coverage this lane exists to add must actually be in the list: a
        // config that quietly dropped one of these would still pass the two
        // set comparisons above only if the file were deleted too.
        assertThat(registered).as("logging-coverage mixins")
                .contains("SheepShearMixin", "CauldronUseMixin", "CakeEatMixin", "AnvilUseMixin")
                // Lane 10 (group D): sponge, coral, plant growth, tamables.
                // Same rationale — these are named so a config edit that drops
                // one while leaving the class on disk cannot pass unnoticed.
                .contains("SpongeAbsorbMixin", "WetSpongeDryMixin",
                        "CoralBlockDeathMixin", "CoralPlantDeathMixin",
                        "CoralFanDeathMixin", "CoralWallFanDeathMixin",
                        "BambooGrowthMixin", "SugarCaneGrowthMixin", "ChorusGrowthMixin",
                        "WolfTameMixin", "CatTameMixin", "CreeperIgniteMixin");
    }

    @Test
    void everyMixinTargetClassResolvesThroughItsOwnImports() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        Path pkgDir = root.resolve(CELL).resolve("src/main/java").resolve(PKG.replace('.', '/'));
        assumeTrue(Files.isDirectory(pkgDir), "mixin package not present");

        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(pkgDir)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = MIXIN_ANNOTATION.matcher(text);
            while (m.find()) {
                String target = m.group(1);
                String simple = target.substring(target.lastIndexOf('.') + 1);
                Set<String> imports = imports(text);

                if (target.contains(".")) {
                    // Fully qualified in the annotation: nothing to import, but a
                    // nested-class reference written with dots would be wrong.
                    continue;
                }
                boolean imported = imports.stream().anyMatch(i -> i.endsWith("." + simple));
                boolean samePackage = Files.isRegularFile(pkgDir.resolve(simple + ".java"));
                boolean implicit = IMPLICIT.stream().anyMatch(p -> p.endsWith("." + simple));

                if (!imported && !samePackage && !implicit) {
                    problems.add(name(file) + " : @Mixin(" + target
                            + ".class) has no import binding " + simple
                            + " — the cell cannot compile, or the mixin applies to nothing");
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("mixin target class does not resolve from its own file's imports:\n"
                    + String.join("\n", problems));
        }
    }

    @Test
    void everyInjectorFailsLoudly() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        Path pkgDir = root.resolve(CELL).resolve("src/main/java").resolve(PKG.replace('.', '/'));
        assumeTrue(Files.isDirectory(pkgDir), "mixin package not present");

        // Pre-existing mixins in this cell predate the require=1 rule and are
        // not this lane's to change. Naming them keeps the assertion strict for
        // everything else instead of widening the rule to nothing.
        Set<String> legacyUnpinned = Set.of("SignChangeMixin", "RavagerMixin",
                "ServerGamePacketListenerAccessor");

        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(pkgDir)) {
            String name = name(file);
            String text = stripLineComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = INJECT.matcher(text);
            while (m.find()) {
                String args = m.group(1);
                Matcher t = METHOD_TARGET.matcher(args);
                if (!t.find()) {
                    continue; // not a method injector
                }
                String target = t.group(1);
                if (legacyUnpinned.contains(name)) {
                    continue;
                }
                if (!args.contains("require = 1")) {
                    problems.add(name + " : @Inject on " + target
                            + " does not declare require = 1, so a mapping change "
                            + "degrades it to a silent no-op");
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("unpinned injector(s):\n" + String.join("\n", problems));
        }
    }

    @Test
    void everyInjectorTargetNamesAMethodOrInitialiser() throws IOException {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        Path pkgDir = root.resolve(CELL).resolve("src/main/java").resolve(PKG.replace('.', '/'));
        assumeTrue(Files.isDirectory(pkgDir), "mixin package not present");

        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(pkgDir)) {
            String text = stripLineComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = INJECT.matcher(text);
            while (m.find()) {
                Matcher t = METHOD_TARGET.matcher(m.group(1));
                if (!t.find()) {
                    continue;
                }
                String target = t.group(1);
                String head = target.split("\\(", 2)[0];
                boolean initialiser = head.equals("<init>") || head.equals("<clinit>");
                // A bare named method has no descriptor, so the injector is
                // resolved by name only — legal, but it cannot distinguish an
                // overload. Only accept it for <init>/<clinit> or an SRG name
                // with a descriptor, so a mapping-sensitive target is explicit.
                boolean hasDescriptor = target.contains("(");
                boolean srg = head.startsWith("m_");
                if (!hasDescriptor) {
                    problems.add(name(file) + " : @Inject target \"" + target
                            + "\" has no descriptor, so it cannot distinguish overloads");
                } else if (!initialiser && !srg) {
                    problems.add(name(file) + " : @Inject target \"" + target
                            + "\" is neither an initialiser nor an SRG method name; "
                            + "the packaged Forge runtime is SRG-mapped");
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("injector target(s) that cannot bind on the packaged runtime:\n"
                    + String.join("\n", problems));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> registeredNames(Path cfgPath) throws IOException {
        String text = Files.readString(cfgPath, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Set<String> out = new LinkedHashSet<>();
        for (String key : List.of("mixins", "server", "client")) {
            Matcher array = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(text);
            if (!array.find()) {
                continue;
            }
            Matcher entry = Pattern.compile("\"([^\"]*)\"").matcher(array.group(1));
            while (entry.find()) {
                out.add(entry.group(1));
            }
        }
        return out;
    }

    /** Every {@code .java} in the package that actually declares {@code @Mixin}. */
    private static Set<String> mixinClassNames(Path pkgDir) throws IOException {
        Set<String> names = new TreeSet<>();
        for (Path file : javaFiles(pkgDir)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (MIXIN_ANNOTATION.matcher(text).find()) {
                names.add(name(file));
            }
        }
        return names;
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Set<String> imports(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IMPORT.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String name(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(".java") ? n.substring(0, n.length() - 5) : n;
    }

    private static String stripLineComments(String text) {
        return text.replaceAll("(?m)//.*$", "");
    }

    private static Path repoRoot() {
        return CoreImportBoundaryTest.repoRoot();
    }
}
