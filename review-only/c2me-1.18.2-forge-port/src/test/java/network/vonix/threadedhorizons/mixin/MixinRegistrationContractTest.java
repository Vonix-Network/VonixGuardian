package network.vonix.threadedhorizons.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MixinRegistrationContractTest {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern INJECTOR = Pattern.compile(
            "@(Inject|Redirect|ModifyArg|ModifyArgs|ModifyVariable|ModifyConstant|Overwrite|Accessor|Invoker)\\b");

    @Test
    void everyListedMixinClassExistsAndHasAnInjector() throws Exception {
        Path jsonPath = Path.of("src/main/resources/threadedhorizons.mixins.json");
        String json = Files.readString(jsonPath);
        List<String> mixins = section(json, "\"mixins\"");
        assertFalse(mixins.isEmpty());
        List<String> missing = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        for (String mixin : mixins) {
            Path source = Path.of("src/main/java/network/vonix/threadedhorizons/mixin/" + mixin.replace('.', '/') + ".java");
            if (!Files.isRegularFile(source)) {
                missing.add(mixin);
                continue;
            }
            String body = Files.readString(source);
            boolean hasInjector = INJECTOR.matcher(body).find();
            boolean duckType = body.contains(" implements ") && body.contains("@Shadow");
            if (!hasInjector && !duckType) {
                empty.add(mixin);
            }
        }
        if (!missing.isEmpty() || !empty.isEmpty()) {
            fail("missing=" + missing + " empty=" + empty);
        }
        assertTrue(section(json, "\"client\"").isEmpty(), "server mixin json must not register client classes");
        assertFalse(anyNestedType(Path.of("src/main/java/network/vonix/threadedhorizons/mixin")),
                "mixin package must not declare nested types that Mixin 0.8.5 refuses to load");
    }

    private static boolean anyNestedType(Path root) throws Exception {
        java.util.regex.Pattern nested = java.util.regex.Pattern.compile(
                "^\\s+(private |public |protected )?(static )?(final )?(class |record |enum |interface )");
        try (var stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".java")).anyMatch(path -> {
                try {
                    return Files.readAllLines(path).stream().anyMatch(line -> nested.matcher(line).find());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    @Test
    void clientMixinsAreClientListedOnly() throws Exception {
        Path jsonPath = Path.of("src/main/resources/threadedhorizons.client.mixins.json");
        String json = Files.readString(jsonPath);
        assertTrue(section(json, "\"mixins\"").isEmpty());
        List<String> client = section(json, "\"client\"");
        assertFalse(client.isEmpty());
        for (String mixin : client) {
            Path source = Path.of("src/main/java/network/vonix/threadedhorizons/client/mixin/" + mixin.replace('.', '/') + ".java");
            assertTrue(Files.isRegularFile(source), mixin);
        }
    }

    private static List<String> section(String json, String key) {
        int keyAt = json.indexOf(key);
        if (keyAt < 0) {
            return List.of();
        }
        int open = json.indexOf('[', keyAt);
        int close = json.indexOf(']', open);
        String block = json.substring(open, close + 1);
        Matcher matcher = QUOTED.matcher(block);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
