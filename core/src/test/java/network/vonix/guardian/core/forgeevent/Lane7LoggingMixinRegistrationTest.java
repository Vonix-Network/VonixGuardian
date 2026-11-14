package network.vonix.guardian.core.forgeevent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structural gate for the LANE 7 producer mixins (sheep shearing, cauldrons,
 * cake, anvils) in the 1.18.2 Forge cell.
 *
 * <h2>Failures this class exists to catch</h2>
 * <p>None of these are visible to a compiler:</p>
 * <ol>
 *   <li><b>Dormant mixin.</b> A class that exists but is absent from
 *       {@code vg.mixins.json} never loads. The build stays green and the
 *       surface stays unlogged — the original defect, unchanged.</li>
 *   <li><b>Silent injector.</b> A {@code @Inject} whose target method or
 *       descriptor does not exist is skipped when {@code require = 0}. This
 *       cell also sets {@code injectors.defaultRequire = 0} in the config, so an
 *       omitted {@code require} inherits the silent behaviour.</li>
 *   <li><b>Wrong mixin target.</b> The {@code @Mixin} class must exist in the
 *       packaged runtime jar, or mixin fails at apply time with an
 *       {@code InvalidMixinException} the operator only sees as a boot crash.</li>
 *   <li><b>Double-logging.</b> Two mixins injecting the same method of the same
 *       target produce two rows for one event, which is worse than the original
 *       gap because the operator cannot tell the rows are duplicates.</li>
 * </ol>
 *
 * <h2>What is deliberately not asserted</h2>
 * <p>No method body, literal, or status code is pinned. Which {@code ActionType}
 * a surface should carry is a behavioural question, settled by the runtime
 * suite; pinning it here would make a correct refactor break the build.</p>
 */
class Lane7LoggingMixinRegistrationTest {

    private static final String CELL = "mc-1.18.2/forge";
    private static final String PKG = "network.vonix.guardian.mc.v1_18_2.forge.mixin";

    /**
     * The mixins this lane owns, as a literal list rather than a directory scan:
     * a scan would pass if a file were deleted and the config edited to match,
     * which is precisely the silent-loss failure being guarded against.
     */
    private static final List<String> LANE_MIXINS = List.of(
            "SheepShearMixin",
            "CauldronUseMixin",
            "CakeEatMixin",
            "AnvilUseMixin"
    );

    /** {@code @Mixin(SomeClass.class)} — simple name, resolved through the file's imports. */
    private static final Pattern MIXIN_TARGET =
            Pattern.compile("@Mixin\\s*\\(\\s*(?:value\\s*=\\s*)?([A-Za-z0-9_.$]+)\\.class");

    /** {@code import a.b.Target;} — used to turn the simple name into a FQCN. */
    private static final Pattern IMPORT = Pattern.compile("^import\\s+([A-Za-z0-9_.$]+);", Pattern.MULTILINE);

    /** {@code require = N} inside an injector annotation. */
    private static final Pattern REQUIRE = Pattern.compile("require\\s*=\\s*(\\d+)");

    /** {@code method = "..."} — the injector's target method signature. */
    private static final Pattern METHOD_ATTR = Pattern.compile("method\\s*=\\s*\"([^\"]+)\"");

    // ------------------------------------------------------------------ registration

    @Test
    void everyLaneMixinIsRegisteredInTheCellConfig() throws IOException {
        Path root = repoRoot();
        Path json = root.resolve(CELL).resolve("src/main/resources/vg.mixins.json");
        assertTrue(Files.isRegularFile(json), "missing " + json);

        String config = Files.readString(json, StandardCharsets.UTF_8);
        List<String> dormant = new ArrayList<>();
        for (String mixin : LANE_MIXINS) {
            if (!config.contains("\"" + mixin + "\"")) {
                dormant.add(mixin);
            }
        }
        if (!dormant.isEmpty()) {
            fail("LANE 7 mixin(s) are not listed in vg.mixins.json, so they never apply: " + dormant);
        }
    }

    @Test
    void everyRegisteredLaneMixinHasASourceFile() throws IOException {
        Path pkgDir = mixinPackageDir();
        List<String> missing = new ArrayList<>();
        for (String mixin : LANE_MIXINS) {
            if (!Files.isRegularFile(pkgDir.resolve(mixin + ".java"))) {
                missing.add(mixin);
            }
        }
        if (!missing.isEmpty()) {
            fail("vg.mixins.json names LANE 7 mixin class(es) with no source file: " + missing);
        }
    }

    /**
     * Every injector must be fail-loud. An unpinned injector inherits this cell's
     * {@code defaultRequire = 0}, so a renamed SRG member would disable the
     * producer silently instead of aborting the apply.
     */
    @Test
    void everyLaneMixinPinsRequireOne() throws IOException {
        Path pkgDir = mixinPackageDir();
        List<String> problems = new ArrayList<>();
        for (String mixin : LANE_MIXINS) {
            Path java = pkgDir.resolve(mixin + ".java");
            if (!Files.isRegularFile(java)) {
                continue;
            }
            // Javadoc here quotes `require = 1` when explaining why it matters, so
            // comment text must be removed before counting or the prose counts as
            // an annotation.
            String code = stripComments(Files.readString(java, StandardCharsets.UTF_8));
            int injectors = countOccurrences(code, "@Inject");
            if (injectors == 0) {
                problems.add(mixin + " declares no @Inject");
                continue;
            }
            int required = 0;
            Matcher m = REQUIRE.matcher(code);
            while (m.find()) {
                if ("1".equals(m.group(1))) {
                    required++;
                } else {
                    problems.add(mixin + " has require = " + m.group(1) + " (must be 1)");
                }
            }
            if (required != injectors) {
                problems.add(mixin + " has " + injectors + " @Inject but " + required
                        + " require = 1; the rest are silent");
            }
        }
        if (!problems.isEmpty()) {
            fail("LANE 7 injectors are not fail-loud:\n" + String.join("\n", problems));
        }
    }

    /**
     * Each mixin must actually record something. A mixin that only observes is
     * the original defect restated: the event is seen and still not logged.
     */
    @Test
    void everyLaneMixinSubmitsAnAction() throws IOException {
        Path pkgDir = mixinPackageDir();
        List<String> silent = new ArrayList<>();
        for (String mixin : LANE_MIXINS) {
            Path java = pkgDir.resolve(mixin + ".java");
            if (!Files.isRegularFile(java)) {
                continue;
            }
            String text = Files.readString(java, StandardCharsets.UTF_8);
            boolean submits = text.contains(".submit(")
                    || text.contains(".submitClick(")
                    || text.contains(".submitBlockPlace(")
                    || text.contains(".submitEntityInteract(");
            if (!submits) {
                silent.add(mixin);
            }
        }
        if (!silent.isEmpty()) {
            fail("LANE 7 mixin(s) observe but never record: " + silent);
        }
    }

    // ------------------------------------------------------------------ resolvability

    /**
     * Every {@code @Mixin} target must be a class the cell can actually apply to.
     *
     * <p>The check reads the packaged 1.18.2 SRG jar that ForgeGradle resolves as
     * this cell's {@code minecraft} dependency — the same artifact the mixin
     * transformer applies against — and asserts a {@code .class} entry exists for
     * the target. That jar carries mojmap class names with SRG member names, so a
     * class entry proves resolvability while saying nothing about the members the
     * injectors bind.</p>
     *
     * <p>Skipped with a message when the Gradle cache is cold, because the jar is
     * a build-time artifact and {@code :core} is deliberately Minecraft-free.</p>
     */
    @Test
    void everyMixinTargetExistsInThePackagedRuntimeJar() throws IOException {
        Path jar = findJoinedSrgJar();
        assumeTrue(jar != null, "no cached 1.18.2 SRG jar; cannot verify @Mixin targets from :core");

        Path pkgDir = mixinPackageDir();
        List<String> problems = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Set<String> entries = entryNames(zip);
            for (String mixin : LANE_MIXINS) {
                Path java = pkgDir.resolve(mixin + ".java");
                if (!Files.isRegularFile(java)) {
                    continue;
                }
                String text = Files.readString(java, StandardCharsets.UTF_8);
                Matcher m = MIXIN_TARGET.matcher(text);
                if (!m.find()) {
                    problems.add(mixin + " has no @Mixin(Target.class)");
                    continue;
                }
                String fqcn = resolveFqcn(text, m.group(1));
                if (fqcn == null) {
                    problems.add(mixin + " targets " + m.group(1)
                            + " but no import supplies its package");
                    continue;
                }
                if (!entries.contains(fqcn.replace('.', '/') + ".class")) {
                    problems.add(mixin + " targets " + fqcn + " which is absent from "
                            + jar.getFileName());
                }
            }
        }
        if (!problems.isEmpty()) {
            fail("mixin target(s) unresolvable in the packaged runtime jar:\n"
                    + String.join("\n", problems));
        }
    }

    /**
     * Two mixins must not inject the same method of the same target: that yields
     * one duplicate row per event. The same file injecting twice into one method
     * is legitimate (a HEAD/RETURN pair around a single call) and is allowed.
     */
    @Test
    void noTwoLaneMixinsInjectTheSameTargetMethod() throws IOException {
        Path pkgDir = mixinPackageDir();
        // key = target class FQCN + "::" + method signature
        Set<String> seenAcrossFiles = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (String mixin : LANE_MIXINS) {
            Path java = pkgDir.resolve(mixin + ".java");
            if (!Files.isRegularFile(java)) {
                continue;
            }
            String text = Files.readString(java, StandardCharsets.UTF_8);
            Matcher targetMatcher = MIXIN_TARGET.matcher(text);
            String target = targetMatcher.find() ? resolveFqcn(text, targetMatcher.group(1)) : null;
            String owner = target == null ? mixin : target;

            Set<String> thisFile = new LinkedHashSet<>();
            Matcher m = METHOD_ATTR.matcher(text);
            while (m.find()) {
                thisFile.add(m.group(1));
            }
            for (String method : thisFile) {
                if (!seenAcrossFiles.add(owner + "::" + method)) {
                    duplicates.add(owner + "::" + method + " (second sighting in " + mixin + ")");
                }
            }
        }
        if (!duplicates.isEmpty()) {
            fail("two LANE 7 mixins inject into the same target method, which double-logs: "
                    + duplicates);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Path repoRoot() {
        Path root = network.vonix.guardian.core.boundary.CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        return root;
    }

    private static Path mixinPackageDir() {
        return repoRoot().resolve(CELL)
                .resolve("src/main/java")
                .resolve(PKG.replace('.', '/'));
    }

    /**
     * @param text     the mixin source
     * @param typeName the name written in {@code @Mixin(...)}, possibly simple
     * @return the fully-qualified name, or {@code null} when the package cannot be
     *         determined from the source
     */
    private static String resolveFqcn(String text, String typeName) {
        if (typeName.contains(".")) {
            return typeName;
        }
        Matcher m = IMPORT.matcher(text);
        while (m.find()) {
            String imported = m.group(1);
            if (imported.endsWith("." + typeName)) {
                return imported;
            }
        }
        return null;
    }

    private static Set<String> entryNames(ZipFile zip) {
        Set<String> names = new LinkedHashSet<>();
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            names.add(e.nextElement().getName());
        }
        return names;
    }

    /**
     * The 1.18.2 {@code joined-*-srg.jar} ForgeGradle produced for this host.
     *
     * @return the newest matching jar, or {@code null} when the cache is cold
     */
    private static Path findJoinedSrgJar() {
        Path joined = Path.of(System.getProperty("user.home"),
                ".gradle/caches/forge_gradle/mcp_repo/net/minecraft/joined");
        if (!Files.isDirectory(joined)) {
            return null;
        }
        try (Stream<Path> versions = Files.list(joined)) {
            return versions
                    .filter(p -> p.getFileName().toString().startsWith("1.18.2-"))
                    .flatMap(p -> {
                        try (Stream<Path> files = Files.list(p)) {
                            return files.filter(f -> f.getFileName().toString().endsWith("-srg.jar"))
                                    .toList().stream();
                        } catch (IOException e) {
                            return Stream.<Path>empty();
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Removes block and line comments so annotation scanning cannot count prose.
     * Deliberately naive: it does not need to understand string literals, because
     * no mixin here contains a comment marker inside a string.
     */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
