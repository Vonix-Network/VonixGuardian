package network.vonix.guardian.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    // ---- defaults / round-trip --------------------------------------------------------------

    @Test
    @DisplayName("defaults() matches README canonical JSON shape")
    void defaultsMatchReadme() {
        GuardianConfig d = GuardianConfig.defaults();
        assertThat(d.database().type()).isEqualTo("sqlite");
        assertThat(d.database().file()).isEqualTo("vonixguardian.db");
        assertThat(d.queue().maxSize()).isEqualTo(50_000);
        assertThat(d.queue().flushIntervalMs()).isEqualTo(5_000L);
        assertThat(d.queue().batchSize()).isEqualTo(1_000);
        assertThat(d.logFile().enabled()).isTrue();
        assertThat(d.logFile().directory()).isEqualTo("logs/vonixguardian");
        assertThat(d.logFile().gzipRotated()).isTrue();
        assertThat(d.logFile().retentionDays()).isEqualTo(30);
        assertThat(d.actions().logBlocks()).isTrue();
        assertThat(d.actions().logSigns()).isTrue();
        assertThat(d.actions().worldBlacklist()).isEmpty();
        assertThat(d.actions().blockBlacklist()).containsExactly("minecraft:air");
        assertThat(d.actions().sourceBlacklist()).isEmpty();
        assertThat(d.permissions().useLuckPerms()).isTrue();
        assertThat(d.permissions().defaultOpLevel()).isEqualTo(3);
        assertThat(d.lookup().defaultPageSize()).isEqualTo(7);
        assertThat(d.lookup().maxRadius()).isEqualTo(10_000);
        assertThat(d.theme()).isEqualTo("aqua");

        // defaults must always validate
        d.validate();
    }

    @Test
    @DisplayName("save() + load() round-trips defaults losslessly")
    void roundTripDefaults(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("config.json");
        GuardianConfig original = GuardianConfig.defaults();
        ConfigLoader.save(p, original);
        GuardianConfig back = ConfigLoader.load(p);
        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("load() on missing file writes defaults to that path")
    void missingFileWritesDefaults(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("nested").resolve("dir").resolve("config.json");
        assertThat(Files.exists(p)).isFalse();

        GuardianConfig loaded = ConfigLoader.load(p);
        assertThat(loaded).isEqualTo(GuardianConfig.defaults());
        assertThat(Files.exists(p)).isTrue();

        String json = Files.readString(p, StandardCharsets.UTF_8);
        // Pretty-printed: two-space indent and at least one newline.
        assertThat(json).contains("\n");
        assertThat(json).contains("  \"database\"");
        assertThat(json).contains("\"sqlite\"");

        // No .tmp leftover.
        try (var stream = Files.list(p.getParent())) {
            assertThat(stream.filter(f -> f.getFileName().toString().endsWith(".tmp")))
                .isEmpty();
        }
    }

    @Test
    @DisplayName("save() writes atomically (no .tmp survives)")
    void saveIsAtomic(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("config.json");
        ConfigLoader.save(p, GuardianConfig.defaults());
        try (var stream = Files.list(tmp)) {
            assertThat(stream.map(f -> f.getFileName().toString()))
                .containsExactly("config.json");
        }
    }

    // ---- comment / lenient parsing ----------------------------------------------------------

    @Test
    @DisplayName("load() strips // line comments and # inline comments")
    void loadStripsComments(@TempDir Path tmp) throws IOException {
        String doc = """
            // top-level comment
            {
              // database section
              "database": { "type": "sqlite", "file": "vg.db", "jdbcUrl": null, "user": null, "password": null },
              "queue":    { "maxSize": 100, "flushIntervalMs": 1000, "batchSize": 10 }, # trailing inline comment
              "logFile":  { "enabled": false, "directory": "logs", "gzipRotated": false, "retentionDays": 0 },
              "actions":  {
                "logBlocks": true, "logContainers": true, "logItems": true,
                "logEntities": true, "logExplosions": true, "logChat": true,
                "logCommands": true, "logSessions": true, "logSigns": true,
                "worldBlacklist": [], "blockBlacklist": [], "sourceBlacklist": []
              },
              "permissions": { "useLuckPerms": false, "defaultOpLevel": 3 },
              "lookup":      { "defaultPageSize": 10, "maxRadius": 500 },
              "theme": "gold"
            }
            """;
        Path p = tmp.resolve("config.json");
        Files.writeString(p, doc, StandardCharsets.UTF_8);

        GuardianConfig cfg = ConfigLoader.load(p);
        assertThat(cfg.theme()).isEqualTo("gold");
        assertThat(cfg.queue().maxSize()).isEqualTo(100);
        assertThat(cfg.lookup().defaultPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("stripComments: // only stripped when first non-whitespace")
    void stripCommentsLeadingSlash() {
        // leading // dropped, including with indent
        assertThat(ConfigLoader.stripComments("  // hi\n{\"a\":1}"))
            .isEqualTo("\n{\"a\":1}");
        // // NOT at line start (after a token) is preserved
        String preserved = "\"url\": \"http://x\"";
        assertThat(ConfigLoader.stripComments(preserved)).isEqualTo(preserved);
    }

    @Test
    @DisplayName("stripComments: '# ' inline only after whitespace or at column 0")
    void stripCommentsInlineHash() {
        // after whitespace -> stripped
        assertThat(ConfigLoader.stripComments("\"k\": 1 # tail"))
            .isEqualTo("\"k\": 1 ");
        // column 0 -> stripped (full-line hash comment)
        assertThat(ConfigLoader.stripComments("# header\n{}"))
            .isEqualTo("\n{}");
        // glued to a token, no preceding whitespace -> preserved
        String preserved = "\"tag\":\"#aqua\"";
        assertThat(ConfigLoader.stripComments(preserved)).isEqualTo(preserved);
    }

    @Test
    @DisplayName("stripComments: handles empty / null / single-line inputs")
    void stripCommentsEdgeCases() {
        assertThat(ConfigLoader.stripComments("")).isEmpty();
        assertThat(ConfigLoader.stripComments(null)).isNull();
        // no trailing newline preserved
        assertThat(ConfigLoader.stripComments("abc")).isEqualTo("abc");
        // multiple blank lines preserved
        assertThat(ConfigLoader.stripComments("\n\n")).isEqualTo("\n\n");
    }

    // ---- validation -------------------------------------------------------------------------

    @Test
    @DisplayName("validate() passes for defaults")
    void validateDefaults() {
        GuardianConfig.defaults().validate();
    }

    static Stream<Arguments> invalidConfigs() {
        GuardianConfig d = GuardianConfig.defaults();
        return Stream.of(
            Arguments.of("bad theme",
                new GuardianConfig(d.database(), d.queue(), d.logFile(), d.actions(),
                    d.permissions(), d.lookup(), "rainbow"),
                "theme"),
            Arguments.of("unknown db type",
                new GuardianConfig(
                    new GuardianConfig.Database("oracle", "x", null, null, null),
                    d.queue(), d.logFile(), d.actions(), d.permissions(), d.lookup(), d.theme()),
                "database.type"),
            Arguments.of("sqlite missing file",
                new GuardianConfig(
                    new GuardianConfig.Database("sqlite", "  ", null, null, null),
                    d.queue(), d.logFile(), d.actions(), d.permissions(), d.lookup(), d.theme()),
                "database.file"),
            Arguments.of("mysql missing jdbc url",
                new GuardianConfig(
                    new GuardianConfig.Database("mysql", null, null, "u", "p"),
                    d.queue(), d.logFile(), d.actions(), d.permissions(), d.lookup(), d.theme()),
                "database.jdbcUrl"),
            Arguments.of("queue.maxSize zero",
                new GuardianConfig(d.database(),
                    new GuardianConfig.Queue(0, 1000, 10),
                    d.logFile(), d.actions(), d.permissions(), d.lookup(), d.theme()),
                "queue.maxSize"),
            Arguments.of("queue.batchSize > maxSize",
                new GuardianConfig(d.database(),
                    new GuardianConfig.Queue(10, 1000, 100),
                    d.logFile(), d.actions(), d.permissions(), d.lookup(), d.theme()),
                "queue.batchSize"),
            Arguments.of("queue.flushIntervalMs zero",
                new GuardianConfig(d.database(),
                    new GuardianConfig.Queue(100, 0, 10),
                    d.logFile(), d.actions(), d.permissions(), d.lookup(), d.theme()),
                "queue.flushIntervalMs"),
            Arguments.of("logFile.retentionDays negative",
                new GuardianConfig(d.database(), d.queue(),
                    new GuardianConfig.LogFile(true, "logs", true, -1),
                    d.actions(), d.permissions(), d.lookup(), d.theme()),
                "logFile.retentionDays"),
            Arguments.of("logFile enabled but blank directory",
                new GuardianConfig(d.database(), d.queue(),
                    new GuardianConfig.LogFile(true, "", true, 0),
                    d.actions(), d.permissions(), d.lookup(), d.theme()),
                "logFile.directory"),
            Arguments.of("defaultOpLevel out of range",
                new GuardianConfig(d.database(), d.queue(), d.logFile(), d.actions(),
                    new GuardianConfig.Permissions(true, 9), d.lookup(), d.theme()),
                "permissions.defaultOpLevel"),
            Arguments.of("lookup.defaultPageSize too high",
                new GuardianConfig(d.database(), d.queue(), d.logFile(), d.actions(),
                    d.permissions(), new GuardianConfig.Lookup(99, 100), d.theme()),
                "lookup.defaultPageSize"),
            Arguments.of("lookup.defaultPageSize too low",
                new GuardianConfig(d.database(), d.queue(), d.logFile(), d.actions(),
                    d.permissions(), new GuardianConfig.Lookup(0, 100), d.theme()),
                "lookup.defaultPageSize"),
            Arguments.of("lookup.maxRadius zero",
                new GuardianConfig(d.database(), d.queue(), d.logFile(), d.actions(),
                    d.permissions(), new GuardianConfig.Lookup(10, 0), d.theme()),
                "lookup.maxRadius"),
            Arguments.of("null blacklist element",
                new GuardianConfig(d.database(), d.queue(), d.logFile(),
                    new GuardianConfig.Actions(true, true, true, true, true, true, true, true, true,
                        new ArrayList<>(), nullableList("minecraft:air", null), new ArrayList<>()),
                    d.permissions(), d.lookup(), d.theme()),
                "actions.blockBlacklist[1]")
        );
    }

    private static List<String> nullableList(String... s) {
        List<String> l = new ArrayList<>();
        for (String x : s) {
            l.add(x);
        }
        return l;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigs")
    @DisplayName("validate() rejects bad configs and names every offender")
    void validateRejectsBad(String label, GuardianConfig cfg, String expectedNeedle) {
        assertThatThrownBy(cfg::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(expectedNeedle);
    }

    @Test
    @DisplayName("validate() aggregates multiple errors in a single exception")
    void validateAggregatesErrors() {
        GuardianConfig bad = new GuardianConfig(
            new GuardianConfig.Database("oracle", null, null, null, null),
            new GuardianConfig.Queue(-1, -1, -1),
            new GuardianConfig.LogFile(true, "", true, -5),
            GuardianConfig.defaults().actions(),
            new GuardianConfig.Permissions(false, 17),
            new GuardianConfig.Lookup(0, 0),
            "neon-pink"
        );
        assertThatThrownBy(bad::validate)
            .isInstanceOf(IllegalStateException.class)
            .satisfies(t -> {
                String msg = t.getMessage();
                assertThat(msg).contains("database.type");
                assertThat(msg).contains("queue.maxSize");
                assertThat(msg).contains("queue.flushIntervalMs");
                assertThat(msg).contains("logFile.retentionDays");
                assertThat(msg).contains("permissions.defaultOpLevel");
                assertThat(msg).contains("lookup.defaultPageSize");
                assertThat(msg).contains("theme");
            });
    }

    @Test
    @DisplayName("load() rejects file whose contents validate-fail")
    void loadValidates(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("config.json");
        // Valid JSON shape, invalid value (bad theme).
        GuardianConfig d = GuardianConfig.defaults();
        GuardianConfig bad = new GuardianConfig(d.database(), d.queue(), d.logFile(),
            d.actions(), d.permissions(), d.lookup(), "neon-pink");
        // Manually save via the same helper (it doesn't validate on write, by design).
        ConfigLoader.save(p, bad);

        assertThatThrownBy(() -> ConfigLoader.load(p))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("theme");
    }
}
