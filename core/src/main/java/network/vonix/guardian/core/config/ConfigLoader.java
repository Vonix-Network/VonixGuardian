package network.vonix.guardian.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Reads and writes {@link GuardianConfig} JSON files.
 *
 * <p>Lenient at read time:
 * <ul>
 *   <li>Whole-line {@code //} comments are stripped</li>
 *   <li>Trailing {@code # ...} end-of-line comments are stripped (must be preceded by whitespace)</li>
 *   <li>Trailing commas inside objects / arrays are tolerated (Gson lenient mode)</li>
 * </ul>
 *
 * <p>Strict at write time: pretty-printed (2-space indent), no comments, atomic
 * via {@code <name>.tmp} + rename. After every {@link #load(Path)} the config is
 * passed through {@link GuardianConfig#validate()}, which throws if anything is
 * out of contract.
 *
 * @since 0.1.0
 */
public final class ConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    private static final Gson STRICT_PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .disableHtmlEscaping()
        .create();

    private static final Gson LENIENT = new GsonBuilder()
        .setLenient()
        .create();

    private ConfigLoader() {
        // utility
    }

    /**
     * Load the config from {@code path}. If the file does not exist, writes
     * {@link GuardianConfig#defaults()} to that path and returns the defaults.
     *
     * <p>The loaded config is validated via {@link GuardianConfig#validate()};
     * an {@link IllegalStateException} is thrown if any invariant is violated.
     *
     * @param path JSON file location (parent directories are created on first write)
     * @return the loaded (or freshly-written defaults) config
     * @throws IOException                   on read / write failure
     * @throws com.google.gson.JsonSyntaxException if the JSON is malformed even after preprocessing
     * @throws IllegalStateException         if the loaded config fails validation
     */
    public static GuardianConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            GuardianConfig defaults = GuardianConfig.defaults();
            LOG.info("Config file {} not found; writing defaults", path);
            save(path, defaults);
            // Defaults are guaranteed valid; still call validate() so behaviour is uniform.
            defaults.validate();
            return defaults;
        }

        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String cleaned = stripComments(raw);
        GuardianConfig cfg;
        try {
            cfg = LENIENT.fromJson(cleaned, GuardianConfig.class);
        } catch (JsonSyntaxException e) {
            throw new JsonSyntaxException("Failed to parse " + path + ": " + e.getMessage(), e);
        }
        if (cfg == null) {
            throw new JsonSyntaxException("Config file " + path + " parsed to null (empty document?)");
        }
        cfg.validate();
        return cfg;
    }

    /**
     * Atomically write {@code config} to {@code path} as pretty-printed JSON.
     *
     * <p>Writes to a sibling {@code .tmp} file, then attempts an atomic move
     * onto the target. Falls back to a non-atomic replace if the filesystem
     * does not support atomic moves.
     *
     * @param path   target file
     * @param config the config to serialise
     * @throws IOException on write failure
     */
    public static void save(Path path, GuardianConfig config) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(config, "config");

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String json = STRICT_PRETTY.toJson(config);
        // Gson's pretty printer uses two-space indent by default — matches the contract.
        byte[] bytes = (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            LOG.debug("Atomic move not supported on {}; falling back to REPLACE_EXISTING", path);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Strip lenient comments from a JSON document.
     *
     * <p>Rules (applied per-line, in order):
     * <ol>
     *   <li>If the line's first non-whitespace characters are {@code //}, the entire line is dropped.</li>
     *   <li>Otherwise, the first occurrence of {@code # } (hash followed by space)
     *       that is preceded by whitespace OR appears at column 0 strips everything
     *       from that point to end-of-line.</li>
     * </ol>
     *
     * <p>String literals are <strong>not</strong> protected — callers must not
     * include the comment markers inside string values they expect to survive.
     * For VonixGuardian's config shape (no user-supplied strings ever contain
     * {@code # } or {@code //} sequences) this is safe and keeps the
     * preprocessor a few dozen lines instead of a real lexer.
     *
     * <p>Visible for testing.
     *
     * @param raw raw JSON-ish text
     * @return text with comments removed; line count is preserved (dropped lines become empty)
     */
    static String stripComments(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String[] lines = raw.split("\n", -1);
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedStart = stripLeadingWhitespace(line);
            if (trimmedStart.startsWith("//")) {
                // drop entire line, but preserve newline structure
            } else {
                int cut = findInlineHashComment(line);
                if (cut >= 0) {
                    out.append(line, 0, cut);
                } else {
                    out.append(line);
                }
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String stripLeadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Find the column index where an inline {@code # } end-of-line comment starts.
     *
     * @return the index of the {@code #}, or -1 if none
     */
    private static int findInlineHashComment(String line) {
        for (int i = 0; i < line.length() - 1; i++) {
            if (line.charAt(i) != '#' || line.charAt(i + 1) != ' ') {
                continue;
            }
            // Either at column 0, or preceded by whitespace.
            if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Convenience wrapper that converts {@link IOException} to
     * {@link UncheckedIOException}; useful from contexts that cannot declare checked IO.
     *
     * @param path config file path
     * @return the loaded config
     */
    public static GuardianConfig loadUnchecked(Path path) {
        try {
            return load(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
