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
        cfg = migrateForwardCompat(cfg);
        cfg.validate();
        return cfg;
    }

    /**
     * Forward-compat fill-ins for fields added in later versions than the on-disk
     * config file predates. Missing/absent fields deserialize to zero-value defaults
     * (int=0, long=0, boolean=false, list=null), which for some fields would
     * silently disable safety features. This method rewrites those zero-values to
     * the current sensible defaults from {@link GuardianConfig#defaults()}.
     *
     * <p>Rewrites (as of 1.1.5):
     * <ul>
     *   <li>{@code actions.entityBlockChangeCoalesceWindowMs == 0} → 500ms (added 1.1.4)</li>
     *   <li>{@code actions.entityBlockChangeMaxTracked == 0} → 8192 (added 1.1.4)</li>
     *   <li>{@code actions.entityChangeAllowlist == null} → empty list (added 1.1.5)</li>
     *   <li>All 13 W5-07 CP-parity toggles absent (all deserialize to {@code false}) →
     *       rewrite to their CP-parity defaults. Absent bool fields deserialize to
     *       {@code false} unconditionally, so we can only distinguish "operator wrote
     *       every one of them false on purpose" from "pre-W5-07 config missing all 13"
     *       by heuristic. Requiring <em>all thirteen</em> to be false to trigger the
     *       backfill makes a false-positive rewrite vanishingly unlikely.</li>
     * </ul>
     *
     * <p>An operator who explicitly wants to <em>disable</em> the coalescer must
     * set {@code entityBlockChangeCoalesceWindowMs} to a negative value, not
     * zero. This is a deliberate footgun-prevention choice: on 200k+ events/sec
     * modpacks (HTTYD, dragon packs), disabling the coalescer will drown the
     * write queue and the operator likely didn't intend to.
     *
     * <p>The {@code entityChangeLogAllEntities} flag has no backfill — it defaults
     * to {@code false} which is the safe post-1.1.5 behavior.
     */
    private static GuardianConfig migrateForwardCompat(GuardianConfig cfg) {
        // Purge forward-compat: older configs pre-W3-B4 have no autoPurgeTime
        // (Gson deserialises absent String to null) — backfill "03:30" to keep
        // validation from firing on the first startup after upgrade.
        // autoPurgeSeconds defaults to 0 (disabled), which is the safe default.
        GuardianConfig work = cfg;
        if (work.purge() != null && work.purge().autoPurgeTime() == null) {
            var p = work.purge();
            var newPurge = new GuardianConfig.Purge(
                p.minAgeSecondsConsole(),
                p.minAgeSecondsInGame(),
                p.autoPurgeSeconds(),
                "03:30"
            );
            LOG.info("Backfilling purge.autoPurgeTime=\"03:30\" (pre-W3-B4 config)");
            work = new GuardianConfig(
                work.database(), work.queue(), work.logFile(), work.actions(),
                work.permissions(), work.lookup(), work.privacy(), newPurge,
                work.storage() == null ? GuardianConfig.Storage.defaults() : work.storage(),
                work.theme(), work.language() == null ? "en_us" : work.language()
            );
        }
        if (work.permissions() != null && work.permissions().perNodeOpLevels() == null) {
            var p = work.permissions();
            LOG.info("Backfilling permissions.perNodeOpLevels={} (pre-W3-B8 config)");
            var newPerms = new GuardianConfig.Permissions(
                p.useLuckPerms(), p.defaultOpLevel(), java.util.Map.of()
            );
            work = new GuardianConfig(
                work.database(), work.queue(), work.logFile(), work.actions(),
                newPerms, work.lookup(), work.privacy(), work.purge(),
                work.storage() == null ? GuardianConfig.Storage.defaults() : work.storage(),
                work.theme(), work.language() == null ? "en_us" : work.language()
            );
        }
        if (work.language() == null) {
            LOG.info("Backfilling language=\"en_us\" (pre-W5-06 config)");
            work = new GuardianConfig(
                work.database(), work.queue(), work.logFile(), work.actions(),
                work.permissions(), work.lookup(), work.privacy(), work.purge(),
                work.storage() == null ? GuardianConfig.Storage.defaults() : work.storage(),
                work.theme(), "en_us"
            );
        }
        // v1.3.1 X1: backfill storage=Storage.defaults() (persistNbt=false, the safe
        // pre-X1 behaviour) when the on-disk config predates the field. Absent JSON
        // key → Gson gives null; we replace with the shipped default so validate()
        // and downstream .storage() readers never see null. Operators who set an
        // explicit storage.persistNbt=true keep their setting.
        if (work.storage() == null) {
            LOG.info("Backfilling storage=Storage.defaults() persistNbt=false (pre-v1.3.1 X1 config)");
            work = new GuardianConfig(
                work.database(), work.queue(), work.logFile(), work.actions(),
                work.permissions(), work.lookup(), work.privacy(), work.purge(),
                GuardianConfig.Storage.defaults(),
                work.theme(), work.language()
            );
        }
        // v1.3.1 X6: backfill database.hikari=Hikari.defaults() when the on-disk config
        // predates the field. Absent JSON key → Gson leaves the record field null; we
        // replace with the shipped defaults so downstream MysqlDao/PostgresDao ctors
        // never NPE on cfg.hikari().maxPoolSize(). Operators who set explicit
        // database.hikari knobs keep their setting.
        if (work.database() != null && work.database().hikari() == null) {
            LOG.info("Backfilling database.hikari=Hikari.defaults() (pre-v1.3.1 X6 config)");
            var db = work.database();
            var newDb = new GuardianConfig.Database(
                db.type(), db.file(), db.jdbcUrl(), db.user(), db.password(),
                db.migrationTarget(), GuardianConfig.Hikari.defaults()
            );
            work = new GuardianConfig(
                newDb, work.queue(), work.logFile(), work.actions(),
                work.permissions(), work.lookup(), work.privacy(), work.purge(),
                work.storage() == null ? GuardianConfig.Storage.defaults() : work.storage(),
                work.theme(), work.language() == null ? "en_us" : work.language()
            );
        }
        if (work.actions() == null) return work;
        var a = work.actions();
        boolean needsRewrite =
                (a.entityBlockChangeCoalesceWindowMs() == 0L) ||
                (a.entityBlockChangeMaxTracked() == 0) ||
                (a.entityChangeAllowlist() == null);
        // W5-07 CP-parity toggles: if ALL 13 came back false, treat as pre-W5-07 config
        // and rewrite to CP defaults. Any explicit true means the operator has already
        // opted in and we should NOT touch anything (they may have deliberately turned
        // some off). See javadoc for the false-positive reasoning.
        boolean w5_07AllFalse =
                !a.logNaturalBreaks() && !a.logTreeGrowth() && !a.logMushroomGrowth()
                && !a.logVineGrowth() && !a.logSculkSpread() && !a.logPortals()
                && !a.logWaterFlow() && !a.logLavaFlow() && !a.logFireExtinguish()
                && !a.logCampfireStart() && !a.logHopperMetaFilter()
                && !a.logDuplicateSuppression() && !a.logCancelledChat();
        if (!needsRewrite && !w5_07AllFalse) return work;

        long window = a.entityBlockChangeCoalesceWindowMs() == 0L
                ? 500L
                : a.entityBlockChangeCoalesceWindowMs();
        int maxTracked = a.entityBlockChangeMaxTracked() == 0
                ? 8192
                : a.entityBlockChangeMaxTracked();
        java.util.List<String> allowlist = a.entityChangeAllowlist() == null
                ? new java.util.ArrayList<>()
                : a.entityChangeAllowlist();
        LOG.info("Backfilling entityBlockChange defaults from pre-1.1.5 config " +
                 "(coalesceWindow={}ms, maxTracked={}, allowlistSize={}); " +
                 "modded mob-griefing recording remains OFF by default — add mod entity keys " +
                 "to entityChangeAllowlist to opt in.",
                 window, maxTracked, allowlist.size());
        if (w5_07AllFalse) {
            LOG.info("Backfilling W5-07 CP-parity toggles from pre-W5-07 config " +
                     "(logNaturalBreaks/TreeGrowth/MushroomGrowth/VineGrowth/SculkSpread/" +
                     "Portals/FireExtinguish/CampfireStart/DuplicateSuppression=true; " +
                     "WaterFlow/LavaFlow/HopperMetaFilter/CancelledChat=false).");
        }
        // Choose CP-parity defaults for W5-07 fields when the config predates them,
        // else preserve the operator's explicit values.
        boolean vNaturalBreaks       = w5_07AllFalse ? true  : a.logNaturalBreaks();
        boolean vTreeGrowth          = w5_07AllFalse ? true  : a.logTreeGrowth();
        boolean vMushroomGrowth      = w5_07AllFalse ? true  : a.logMushroomGrowth();
        boolean vVineGrowth          = w5_07AllFalse ? true  : a.logVineGrowth();
        boolean vSculkSpread         = w5_07AllFalse ? true  : a.logSculkSpread();
        boolean vPortals             = w5_07AllFalse ? true  : a.logPortals();
        boolean vWaterFlow           = w5_07AllFalse ? false : a.logWaterFlow();
        boolean vLavaFlow            = w5_07AllFalse ? false : a.logLavaFlow();
        boolean vFireExtinguish      = w5_07AllFalse ? true  : a.logFireExtinguish();
        boolean vCampfireStart       = w5_07AllFalse ? true  : a.logCampfireStart();
        boolean vHopperMetaFilter    = w5_07AllFalse ? false : a.logHopperMetaFilter();
        boolean vDuplicateSuppression= w5_07AllFalse ? true  : a.logDuplicateSuppression();
        boolean vCancelledChat       = w5_07AllFalse ? false : a.logCancelledChat();
        // v1.3.0 W4: mixinHotEvents defaults ON. If the config predates W4, the field is
        // absent → Gson deserialises to false → we backfill to true. Heuristic: a config that
        // ALSO has every W5-07 toggle false almost certainly predates W4 (W5-07 shipped in
        // v1.2.6, W4 in v1.3.0). We conservatively only backfill mixinHotEvents to true when
        // we're already convinced this is a pre-W5-07 (and therefore pre-W4) config; otherwise
        // we honor the explicit operator value. An operator who intentionally disables the
        // kill-switch on a modern config keeps their setting.
        boolean vMixinHotEvents      = w5_07AllFalse ? true  : a.mixinHotEvents();
        var newActions = new GuardianConfig.Actions(
                a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(),
                a.logExplosions(), a.logChat(), a.logCommands(), a.logSessions(),
                a.logSigns(), a.logInteractions(), a.logWorldEvents(),
                a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(),
                window, maxTracked,
                allowlist, a.entityChangeLogAllEntities(),
                vNaturalBreaks, vTreeGrowth, vMushroomGrowth, vVineGrowth,
                vSculkSpread, vPortals, vWaterFlow, vLavaFlow,
                vFireExtinguish, vCampfireStart, vHopperMetaFilter,
                vDuplicateSuppression, vCancelledChat,
                vMixinHotEvents
        );
        return new GuardianConfig(
                work.database(), work.queue(), work.logFile(), newActions,
                work.permissions(), work.lookup(), work.privacy(), work.purge(),
                work.storage(),
                work.theme(), work.language()
        );
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
