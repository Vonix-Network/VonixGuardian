package network.vonix.guardian.core.event;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Packaged Mixin APPLY rejects non-private ordinary methods merged into the
 * target class. HopperBlockEntityMixin may keep injector handlers, but it must
 * not export helpers such as {@code vg$snapshot}.
 */
class HopperMixinOrdinaryMethodVisibilityStructuralTest {

    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/mixin/HopperBlockEntityMixin.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/mixin/HopperBlockEntityMixin.java",
            "mc-1.21.1/neoforge/src/main/java/network/vonix/guardian/mc/v1_21_1/neoforge/mixin/HopperBlockEntityMixin.java",
            "mc-26.1.2/neoforge/src/main/java/network/vonix/guardian/mc/v26_1/neoforge/mixin/HopperBlockEntityMixin.java"
    );

    private static final Set<String> MIXIN_HANDLER_ANNOTATIONS = Set.of(
            "Inject",
            "Redirect",
            "ModifyArg",
            "ModifyArgs",
            "ModifyVariable",
            "ModifyConstant",
            "Overwrite",
            "Shadow",
            "Accessor",
            "Invoker",
            "Surrogate",
            "ModifyReturnValue",
            "WrapOperation",
            "WrapWithCondition",
            "WrapMethod",
            "ModifyExpressionValue"
    );

    private static final Pattern METHOD = Pattern.compile(
            "^(?:(public|protected|private)\\s+)?(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                    + "(?!class\\b)[\\w.<>,\\[\\]?\\s]+\\s+([\\w$]+)\\s*\\("
    );

    private static final Pattern ANNOTATION_NAME = Pattern.compile("^@([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    void hopperMixinsMustNotExportOrdinaryHelpers() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        assertThat(CELLS).hasSize(9);
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path);
            assertThat(text).as(cell + " must not export vg$snapshot")
                    .doesNotContain("vg$snapshot");
            List<String> exported = ordinaryNonPrivateMethods(text);
            assertThat(exported)
                    .as(cell + " must not contain non-private ordinary Mixin methods")
                    .isEmpty();
        }
    }

    @Test
    void scannerRejectsPackagePrivateSnapshotAndAllowsInjectors() {
        String leaking = """
                @Mixin(HopperBlockEntity.class)
                public abstract class HopperBlockEntityMixin {
                    @Inject(
                            method = "ejectItems",
                            at = @At("HEAD"),
                            require = 0
                    )
                    private static void vg$beforeEjectItems() {}

                    static java.util.Map<Integer, ItemStack> vg$snapshot(Container c) {
                        return java.util.Map.of();
                    }
                }
                """;
        assertThat(ordinaryNonPrivateMethods(leaking)).containsExactly("vg$snapshot");

        String clean = """
                @Mixin(HopperBlockEntity.class)
                public abstract class HopperBlockEntityMixin {
                    @Inject(method = "ejectItems", at = @At("HEAD"), require = 0)
                    private static void vg$beforeEjectItems() {}
                }
                """;
        assertThat(ordinaryNonPrivateMethods(clean)).isEmpty();
    }

    static List<String> ordinaryNonPrivateMethods(String source) {
        List<String> exported = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        StringBuilder annotation = new StringBuilder();
        boolean inAnnotation = false;
        int paren = 0;
        for (String raw : source.split("\n", -1)) {
            String line = stripLineComment(raw).trim();
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("/*") || line.startsWith("*/")) {
                continue;
            }
            if (inAnnotation) {
                annotation.append(line);
                paren += count(line, '(') - count(line, ')');
                if (paren <= 0) {
                    pending.add(annotation.toString());
                    annotation.setLength(0);
                    inAnnotation = false;
                }
                continue;
            }
            if (line.startsWith("@")) {
                annotation.append(line);
                paren = count(line, '(') - count(line, ')');
                if (paren > 0) {
                    inAnnotation = true;
                } else {
                    pending.add(annotation.toString());
                    annotation.setLength(0);
                }
                continue;
            }
            Matcher method = METHOD.matcher(line);
            if (method.find()) {
                String visibility = method.group(1);
                String name = method.group(2);
                boolean handler = pending.stream().anyMatch(HopperMixinOrdinaryMethodVisibilityStructuralTest::isMixinHandler);
                boolean nonPrivate = visibility == null
                        || "public".equals(visibility)
                        || "protected".equals(visibility);
                if (!handler && nonPrivate && !name.equals("HopperBlockEntityMixin")) {
                    exported.add(name);
                }
                pending.clear();
                continue;
            }
            if (line.endsWith("{") || line.endsWith(";")) {
                pending.clear();
            }
        }
        return exported;
    }

    private static boolean isMixinHandler(String annotation) {
        Matcher matcher = ANNOTATION_NAME.matcher(annotation);
        if (!matcher.find()) {
            return false;
        }
        return MIXIN_HANDLER_ANNOTATIONS.contains(matcher.group(1));
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("//");
        return idx < 0 ? line : line.substring(0, idx);
    }

    private static int count(String text, char ch) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                n++;
            }
        }
        return n;
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/fabric"))) {
                return p;
            }
        }
        return null;
    }
}
