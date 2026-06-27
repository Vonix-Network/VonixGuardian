package network.vonix.guardian.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Top-level configuration record for VonixGuardian.
 *
 * <p>Mirrors SHARED-CONTRACTS &sect; 9 verbatim. The JSON shape persisted to
 * {@code config/vonixguardian/config.json} is governed by the README
 * &sect; Configuration section.
 *
 * <p>Instances are deeply immutable: every nested type is a {@code record}. The
 * mutable list fields in {@link Actions} are shallow-copied through
 * {@link #defaults()} but callers MUST treat all collections as read-only.
 *
 * @param database     storage backend selection + connection details
 * @param queue        async writer queue sizing
 * @param logFile      JSON-Lines audit log file settings
 * @param actions      per-category logging toggles + blacklists
 * @param permissions  permission resolution settings
 * @param lookup       {@code /vg lookup} UX settings
 * @param theme        chat theme key; must be a known {@link ThemeRegistry} entry
 * @since 0.1.0
 */
public record GuardianConfig(
    Database database,
    Queue queue,
    LogFile logFile,
    Actions actions,
    Permissions permissions,
    Lookup lookup,
    String theme
) {

    /** Known theme keys (matches the theme registry shipped in {@code core.theme}). */
    public static final Set<String> KNOWN_THEMES =
        Set.of("aqua", "blue", "gold", "green", "purple", "red", "white");

    /** Known database backend types. */
    public static final Set<String> KNOWN_DB_TYPES =
        Set.of("sqlite", "mysql", "postgresql");

    /**
     * Database connection settings.
     *
     * @param type     one of {@code sqlite}, {@code mysql}, {@code postgresql}
     * @param file     SQLite file path (relative to the server data dir); ignored for non-SQLite
     * @param jdbcUrl  JDBC URL for {@code mysql} / {@code postgresql}; may be {@code null} for SQLite
     * @param user     DB user; may be {@code null} for SQLite
     * @param password DB password; may be {@code null} for SQLite
     */
    public record Database(String type, String file, String jdbcUrl, String user, String password) {}

    /**
     * Async write queue settings.
     *
     * @param maxSize         max queued actions before backpressure drops with WARN
     * @param flushIntervalMs polling / max-age before forcing a flush
     * @param batchSize       preferred batch size for a flush
     */
    public record Queue(int maxSize, long flushIntervalMs, int batchSize) {}

    /**
     * JSON-Lines audit log file settings.
     *
     * @param enabled        whether the rolling file is written
     * @param directory      relative or absolute directory containing daily files
     * @param gzipRotated    gzip yesterday's file on rotation
     * @param retentionDays  retention horizon; {@code 0} = keep forever
     */
    public record LogFile(boolean enabled, String directory, boolean gzipRotated, int retentionDays) {}

    /**
     * Per-category logging toggles and exclusion lists.
     *
     * @param logBlocks       master toggle for block place / break
     * @param logContainers   master toggle for container transactions
     * @param logItems        master toggle for item drop / pickup
     * @param logEntities     master toggle for entity kills
     * @param logExplosions   master toggle for explosion events
     * @param logChat         master toggle for chat
     * @param logCommands     master toggle for commands
     * @param logSessions     master toggle for join / quit
     * @param logSigns        master toggle for sign edits
     * @param worldBlacklist  world keys (e.g. {@code minecraft:overworld}) to skip
     * @param blockBlacklist  block ids to skip (e.g. {@code minecraft:air})
     * @param sourceBlacklist sourceTag values to skip (e.g. {@code explosion:tnt})
     */
    public record Actions(
        boolean logBlocks,
        boolean logContainers,
        boolean logItems,
        boolean logEntities,
        boolean logExplosions,
        boolean logChat,
        boolean logCommands,
        boolean logSessions,
        boolean logSigns,
        List<String> worldBlacklist,
        List<String> blockBlacklist,
        List<String> sourceBlacklist
    ) {}

    /**
     * Permission resolution settings.
     *
     * @param useLuckPerms   try to bridge LuckPerms via reflection
     * @param defaultOpLevel fallback permission level required when LP is absent
     */
    public record Permissions(boolean useLuckPerms, int defaultOpLevel) {}

    /**
     * {@code /vg lookup} UX settings.
     *
     * @param defaultPageSize rows per page (1..50)
     * @param maxRadius       max radius accepted in {@code r:<n>}
     */
    public record Lookup(int defaultPageSize, int maxRadius) {}

    /**
     * Build the canonical default config matching README &sect; Configuration.
     *
     * @return a freshly-populated default config
     */
    public static GuardianConfig defaults() {
        List<String> worldBlacklist = new ArrayList<>();
        List<String> blockBlacklist = new ArrayList<>(List.of("minecraft:air"));
        List<String> sourceBlacklist = new ArrayList<>();
        return new GuardianConfig(
            new Database("sqlite", "vonixguardian.db", null, null, null),
            new Queue(50_000, 5_000L, 1_000),
            new LogFile(true, "logs/vonixguardian", true, 30),
            new Actions(
                true, true, true, true, true, true, true, true, true,
                worldBlacklist, blockBlacklist, sourceBlacklist
            ),
            new Permissions(true, 3),
            new Lookup(7, 10_000),
            "aqua"
        );
    }

    /**
     * Validate this config and throw a single aggregate exception listing every problem.
     *
     * <p>Invariants enforced:
     * <ul>
     *   <li>{@code database.type} ∈ {@link #KNOWN_DB_TYPES}</li>
     *   <li>If {@code database.type == "sqlite"}: {@code database.file} non-blank</li>
     *   <li>If {@code database.type} ∈ {mysql, postgresql}: {@code database.jdbcUrl} non-blank</li>
     *   <li>{@code queue.maxSize > 0}</li>
     *   <li>{@code queue.batchSize > 0} and {@code queue.batchSize <= queue.maxSize}</li>
     *   <li>{@code queue.flushIntervalMs > 0}</li>
     *   <li>{@code logFile.retentionDays >= 0}</li>
     *   <li>If {@code logFile.enabled}: {@code logFile.directory} non-blank</li>
     *   <li>{@code permissions.defaultOpLevel} ∈ [0, 4]</li>
     *   <li>{@code lookup.defaultPageSize} ∈ [1, 50]</li>
     *   <li>{@code lookup.maxRadius >= 1}</li>
     *   <li>{@code theme} ∈ {@link #KNOWN_THEMES}</li>
     *   <li>No {@code null} elements in any of the {@link Actions} blacklists</li>
     * </ul>
     *
     * @throws IllegalStateException if any problem is detected; the message lists every problem
     */
    public void validate() {
        List<String> errors = new ArrayList<>();

        if (database == null) {
            errors.add("database: section missing");
        } else {
            if (database.type == null || !KNOWN_DB_TYPES.contains(database.type)) {
                errors.add("database.type: must be one of " + KNOWN_DB_TYPES + " (got " + database.type + ")");
            } else if ("sqlite".equals(database.type)) {
                if (isBlank(database.file)) {
                    errors.add("database.file: must be non-blank for sqlite backend");
                }
            } else {
                if (isBlank(database.jdbcUrl)) {
                    errors.add("database.jdbcUrl: must be non-blank for " + database.type + " backend");
                }
            }
        }

        if (queue == null) {
            errors.add("queue: section missing");
        } else {
            if (queue.maxSize <= 0) {
                errors.add("queue.maxSize: must be > 0 (got " + queue.maxSize + ")");
            }
            if (queue.batchSize <= 0) {
                errors.add("queue.batchSize: must be > 0 (got " + queue.batchSize + ")");
            }
            if (queue.maxSize > 0 && queue.batchSize > queue.maxSize) {
                errors.add("queue.batchSize: must be <= queue.maxSize (got "
                    + queue.batchSize + " > " + queue.maxSize + ")");
            }
            if (queue.flushIntervalMs <= 0L) {
                errors.add("queue.flushIntervalMs: must be > 0 (got " + queue.flushIntervalMs + ")");
            }
        }

        if (logFile == null) {
            errors.add("logFile: section missing");
        } else {
            if (logFile.retentionDays < 0) {
                errors.add("logFile.retentionDays: must be >= 0 (got " + logFile.retentionDays + ")");
            }
            if (logFile.enabled && isBlank(logFile.directory)) {
                errors.add("logFile.directory: must be non-blank when logFile.enabled is true");
            }
        }

        if (actions == null) {
            errors.add("actions: section missing");
        } else {
            checkNoNullElems(errors, "actions.worldBlacklist", actions.worldBlacklist);
            checkNoNullElems(errors, "actions.blockBlacklist", actions.blockBlacklist);
            checkNoNullElems(errors, "actions.sourceBlacklist", actions.sourceBlacklist);
        }

        if (permissions == null) {
            errors.add("permissions: section missing");
        } else if (permissions.defaultOpLevel < 0 || permissions.defaultOpLevel > 4) {
            errors.add("permissions.defaultOpLevel: must be in [0,4] (got " + permissions.defaultOpLevel + ")");
        }

        if (lookup == null) {
            errors.add("lookup: section missing");
        } else {
            if (lookup.defaultPageSize < 1 || lookup.defaultPageSize > 50) {
                errors.add("lookup.defaultPageSize: must be in [1,50] (got " + lookup.defaultPageSize + ")");
            }
            if (lookup.maxRadius < 1) {
                errors.add("lookup.maxRadius: must be >= 1 (got " + lookup.maxRadius + ")");
            }
        }

        if (theme == null || !KNOWN_THEMES.contains(theme)) {
            errors.add("theme: must be one of " + KNOWN_THEMES + " (got " + theme + ")");
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("GuardianConfig validation failed (")
                .append(errors.size()).append(" problem")
                .append(errors.size() == 1 ? "" : "s").append("):");
            for (String e : errors) {
                sb.append("\n  - ").append(e);
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void checkNoNullElems(List<String> errors, String field, List<String> list) {
        if (list == null) {
            return; // null lists are tolerated (Gson may emit null for absent JSON arrays); treat as empty
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null) {
                errors.add(field + "[" + i + "]: null element not allowed");
            }
        }
    }
}
