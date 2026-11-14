package network.vonix.guardian.core.boundary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structural gate for the LANE 9 logging-coverage mixins: honeycomb waxing, axe
 * wax-off (scraping), beds, and lecterns.
 *
 * <p>These four surfaces share a failure mode compilation cannot catch: a mixin
 * that exists, compiles, and does nothing. That happens when the class is not
 * listed in the cell's mixin config (never applied), when an {@code @Inject}
 * names a method the runtime does not have (applied but never fires), or when
 * {@code require = 0} is used, which turns a broken mapping into a silent no-op
 * instead of a boot failure.</p>
 *
 * <p>Nothing here pins prose. The assertions are wiring and contract:</p>
 * <ol>
 *   <li>every mixin this lane adds is registered in {@code vg.mixins.json};</li>
 *   <li>each names a real {@code @Mixin} target and declares an injector;</li>
 *   <li>every injector in each carries {@code require = 1}, and any injector that
 *       binds an SRG descriptor by name also sets {@code remap = false}; and</li>
 *   <li>when Minecraft is on the test classpath, each {@code @Mixin} target
 *       resolves and each bound SRG descriptor is a real declared method.</li>
 * </ol>
 *
 * <p>(4) is the valuable one. A {@code @Mixin} target is checked by
 * {@code Class.forName}, but the <em>method</em> a mixin injects into is a string.
 * A wrong descriptor there is invisible to {@code compileJava} and surfaces only
 * as a boot-time {@code InvalidInjectionException} — or, with {@code require = 0},
 * as nothing at all. Reading the resolved class's declared methods and comparing
 * JVM descriptors turns that into a build-time failure.</p>
 */
class ForgeInteractionMixinRegistrationTest {

    /** Every mixin this lane adds, with the SRG descriptors it binds. */
    private static final List<LaneMixin> LANE_MIXINS = List.of(
            new LaneMixin("HoneycombItemMixin",
                    "net.minecraft.world.item.HoneycombItem",
                    "honeycomb wax-on",
                    List.of("m_6225_(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;")),
            new LaneMixin("AxeItemMixin",
                    "net.minecraft.world.item.AxeItem",
                    "axe wax-off (scrape)",
                    List.of("m_6225_(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;")),
            new LaneMixin("BedBlockMixin",
                    "net.minecraft.world.level.block.BedBlock",
                    "bed partner half + bed explosion",
                    List.of("m_5707_(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)V",
                            "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;")),
            new LaneMixin("LecternBookMixin",
                    "net.minecraft.world.level.block.entity.LecternBlockEntity$1",
                    "lectern book taken (container adapter)",
                    List.of("m_8016_(I)Lnet/minecraft/world/item/ItemStack;",
                            "m_7407_(II)Lnet/minecraft/world/item/ItemStack;")),
            new LaneMixin("LecternMenuTakeMixin",
                    "net.minecraft.world.inventory.LecternMenu",
                    "lectern book taken (player attribution)",
                    List.of("m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z"))
    );

    /** The 1.18.2 Forge cell this lane owns. */
    private static final String CELL = "mc-1.18.2/forge";

    private static final String MIXIN_PKG =
            "src/main/java/network/vonix/guardian/mc/v1_18_2/forge/mixin/";

    /** Any injector annotation whose body must therefore carry {@code require = 1}. */
    private static final Pattern INJECTOR = Pattern.compile(
            "@(?:Inject|Redirect|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant|WrapOperation)\\s*\\(");

    private record LaneMixin(String simpleName, String targetClass, String surface,
                             List<String> descriptors) { }

    @Test
    void everyLaneMixinIsRegisteredInTheCellConfig() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        Path cfg = root.resolve(CELL).resolve("src/main/resources/vg.mixins.json");
        assumeTrue(Files.isRegularFile(cfg), "no vg.mixins.json in " + CELL);

        var parsed = parseFlatConfig(cfg);
        List<String> registered = new ArrayList<>();
        registered.addAll(stringList(parsed, "mixins"));
        registered.addAll(stringList(parsed, "server"));
        registered.addAll(stringList(parsed, "client"));

        List<String> missing = new ArrayList<>();
        for (LaneMixin m : LANE_MIXINS) {
            if (!registered.contains(m.simpleName())) {
                missing.add(m.simpleName() + " (" + m.surface() + ")");
            }
        }
        assertTrue(missing.isEmpty(),
                "unregistered mixin(s) never apply, so the surface stays unlogged: " + missing);
    }

    @Test
    void everyLaneMixinNamesItsTargetAndDeclaresAnInjector() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (LaneMixin m : LANE_MIXINS) {
            Path java = root.resolve(CELL).resolve(MIXIN_PKG + m.simpleName() + ".java");
            if (!Files.isRegularFile(java)) {
                problems.add(m.simpleName() + " : file missing at " + root.relativize(java));
                continue;
            }
            String src = Files.readString(java, StandardCharsets.UTF_8);
            if (!src.contains("@Mixin(")) {
                problems.add(m.simpleName() + " : no @Mixin declaration");
            }
            if (!src.contains(m.targetClass())) {
                problems.add(m.simpleName() + " : does not name target " + m.targetClass());
            }
            if (!INJECTOR.matcher(src).find()) {
                problems.add(m.simpleName() + " : declares no injector, so it cannot produce a row");
            }
            for (String descriptor : m.descriptors()) {
                if (!src.contains(descriptor)) {
                    problems.add(m.simpleName() + " : does not bind expected SRG target " + descriptor);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * A {@code require = 0} injector is dormant by construction: mixin treats a
     * missing target as acceptable and the hook silently never runs. Counting
     * rather than parsing annotation bodies avoids a regex over descriptors that
     * themselves contain {@code ;} and {@code )}.
     */
    @Test
    void everyLaneMixinInjectorRequiresOne() throws IOException {
        Path root = CoreImportBoundaryTest.repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (LaneMixin m : LANE_MIXINS) {
            Path java = root.resolve(CELL).resolve(MIXIN_PKG + m.simpleName() + ".java");
            if (!Files.isRegularFile(java)) continue; // reported by the previous test
            String src = Files.readString(java, StandardCharsets.UTF_8);

            int injectors = countMatches(INJECTOR, src);
            int requires = countMatches(Pattern.compile("require\\s*=\\s*1"), src);
            if (injectors == 0) {
                problems.add(m.simpleName() + " : no injector to check");
                continue;
            }
            if (requires < injectors) {
                problems.add(m.simpleName() + " : " + injectors + " injector(s) but only "
                        + requires + " require = 1 — a mapping change would be silent, not fatal");
            }
            if (src.contains("require = 0")) {
                problems.add(m.simpleName() + " : uses require = 0");
            }
            // Every named SRG binding must opt out of remapping, otherwise the
            // refmap lookup rewrites a name that is already the runtime name.
            int namedMethods = countMatches(Pattern.compile("method\\s*="), src);
            int remapFalse = countMatches(Pattern.compile("remap\\s*=\\s*false"), src);
            if (namedMethods > remapFalse) {
                problems.add(m.simpleName() + " : " + namedMethods + " named method binding(s) but only "
                        + remapFalse + " remap = false");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * Verifies the {@code @Mixin} targets resolve and that each SRG descriptor the
     * injectors bind is a real declared method on that target.
     *
     * <p>Skipped, not failed, when the Minecraft artifact is absent: the
     * {@code :core} test source set does not depend on Minecraft classes, so
     * requiring them would fail every run that is not a full cell build.</p>
     */
    @Test
    void everyInjectedTargetAndDescriptorResolvesOnTheCellClasspath() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        assumeTrue(minecraftOnClasspath(loader),
                "Minecraft classes not on the core test classpath — descriptor check skipped");

        List<String> unresolved = new ArrayList<>();
        for (LaneMixin m : LANE_MIXINS) {
            Class<?> target;
            try {
                target = Class.forName(m.targetClass(), false, loader);
            } catch (Throwable t) {
                unresolved.add(m.simpleName() + " : @Mixin target " + m.targetClass()
                        + " does not resolve (" + t.getClass().getSimpleName() + ")");
                continue;
            }
            for (String descriptor : m.descriptors()) {
                if (!hasDescriptor(target, descriptor)) {
                    unresolved.add(m.simpleName() + " : " + m.targetClass()
                            + " has no method " + descriptor);
                }
            }
        }
        assertTrue(unresolved.isEmpty(),
                "injector targets absent from the packaged runtime:\n"
                        + String.join("\n", unresolved));
    }

    private static boolean minecraftOnClasspath(ClassLoader loader) {
        try {
            Class.forName("net.minecraft.core.BlockPos", false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Minimal reader for a mixin config: top-level string members and top-level
     * string arrays, which is all this test needs. Duplicated in this file rather
     * than borrowed from the neighbouring registration test so an edit there
     * cannot silently change what this lane asserts.
     *
     * @return top-level member name to value; array values are {@code List<String>}
     */
    private static java.util.Map<String, Object> parseFlatConfig(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String stripped = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
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
    private static List<String> stringList(java.util.Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * @param type       class to inspect
     * @param descriptor SRG name plus JVM descriptor, e.g.
     *                   {@code m_6225_(Lnet/minecraft/...;)Lnet/minecraft/...;}
     * @return {@code true} when some method in the hierarchy matches name and descriptor
     */
    private static boolean hasDescriptor(Class<?> type, String descriptor) {
        int paren = descriptor.indexOf('(');
        if (paren <= 0) return false;
        String name = descriptor.substring(0, paren);
        String desc = descriptor.substring(paren);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && descriptorOf(method).equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String descriptorOf(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : method.getParameterTypes()) {
            sb.append(typeDescriptor(p));
        }
        sb.append(')').append(typeDescriptor(method.getReturnType()));
        return sb.toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return "[" + typeDescriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
