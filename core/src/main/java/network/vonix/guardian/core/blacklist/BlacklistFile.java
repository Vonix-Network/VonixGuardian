package network.vonix.guardian.core.blacklist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Parses a CoreProtect-compatible {@code blacklist.txt} file.
 *
 * <p>Grammar (one rule per line, blank/comment lines ignored):
 * <pre>
 *   user:&lt;player_name&gt;
 *   user_uuid:&lt;uuid&gt;
 *   command:&lt;name&gt;
 *   block:&lt;namespaced_id&gt;
 *   entity:&lt;namespaced_id&gt;
 *   &lt;block_or_entity_id&gt;@&lt;user_name&gt;
 * </pre>
 *
 * <p>Comments start with {@code #}. Unknown rule kinds log a WARN and are
 * skipped; a bad file never aborts boot.
 *
 * @since 1.1.7 (W3-B6)
 */
public final class BlacklistFile {

    private static final Logger LOG = LoggerFactory.getLogger(BlacklistFile.class);

    /** Parsed composite rule: both {@code id} and {@code user} must match. */
    public record Composite(String id, String userName) {
        public Composite {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(userName, "userName");
        }
    }

    /** Immutable parsed contents of a blacklist.txt. */
    public record Parsed(
            Set<String> userNames,
            Set<UUID> userUuids,
            Set<String> commandPrefixes,
            Set<String> blockIds,
            Set<String> entityIds,
            Set<Composite> composites
    ) {
        public Parsed {
            userNames       = Set.copyOf(userNames);
            userUuids       = Set.copyOf(userUuids);
            commandPrefixes = Set.copyOf(commandPrefixes);
            blockIds        = Set.copyOf(blockIds);
            entityIds       = Set.copyOf(entityIds);
            composites      = Set.copyOf(composites);
        }

        /** Total number of parsed rules across all kinds. */
        public int size() {
            return userNames.size() + userUuids.size() + commandPrefixes.size()
                 + blockIds.size() + entityIds.size() + composites.size();
        }

        /** Empty rule set. */
        public static Parsed empty() {
            return new Parsed(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }
    }

    private BlacklistFile() {}

    /**
     * Load and parse a blacklist file. Returns {@link Parsed#empty()} if the
     * file does not exist.
     *
     * @param path absolute path to {@code blacklist.txt}
     * @return parsed rules (never null)
     * @throws IOException if the file cannot be read
     */
    public static Parsed load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return Parsed.empty();
        }
        List<String> lines = Files.readAllLines(path);
        return parse(lines);
    }

    /**
     * Parse an already-loaded sequence of lines (for tests and hot-reload).
     *
     * @param lines raw lines (never null)
     * @return parsed rule set
     */
    public static Parsed parse(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        Set<String> users   = new LinkedHashSet<>();
        Set<UUID>   uuids   = new LinkedHashSet<>();
        Set<String> cmds    = new LinkedHashSet<>();
        Set<String> blocks  = new LinkedHashSet<>();
        Set<String> ents    = new LinkedHashSet<>();
        Set<Composite> comps = new LinkedHashSet<>();

        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Composite form: id@user (id may contain ':')
            int at = line.indexOf('@');
            int colon = line.indexOf(':');
            if (at > 0 && (colon < 0 || at < colon || !isKnownPrefix(line.substring(0, colon)))) {
                String id = line.substring(0, at).trim();
                String user = line.substring(at + 1).trim();
                if (id.isEmpty() || user.isEmpty()) {
                    LOG.warn("blacklist.txt:{}: malformed composite '{}' — skipped", lineNo, line);
                    continue;
                }
                comps.add(new Composite(id.toLowerCase(Locale.ROOT), user));
                continue;
            }

            if (colon < 0) {
                LOG.warn("blacklist.txt:{}: unknown rule '{}' — skipped", lineNo, line);
                continue;
            }
            String kind = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val  = line.substring(colon + 1).trim();
            if (val.isEmpty()) {
                LOG.warn("blacklist.txt:{}: empty value for '{}' — skipped", lineNo, kind);
                continue;
            }
            switch (kind) {
                case "user"      -> users.add(val);
                case "user_uuid" -> {
                    try {
                        uuids.add(UUID.fromString(val));
                    } catch (IllegalArgumentException e) {
                        LOG.warn("blacklist.txt:{}: bad UUID '{}' — skipped", lineNo, val);
                    }
                }
                case "command"   -> cmds.add(val.toLowerCase(Locale.ROOT));
                case "block"     -> blocks.add(val.toLowerCase(Locale.ROOT));
                case "entity"    -> ents.add(val.toLowerCase(Locale.ROOT));
                default          -> LOG.warn("blacklist.txt:{}: unknown rule kind '{}' — skipped",
                                             lineNo, kind);
            }
        }

        return new Parsed(users, uuids, cmds, blocks, ents, comps);
    }

    private static boolean isKnownPrefix(String p) {
        if (p == null) return false;
        String s = p.trim().toLowerCase(Locale.ROOT);
        return s.equals("user") || s.equals("user_uuid") || s.equals("command")
            || s.equals("block") || s.equals("entity");
    }
}
