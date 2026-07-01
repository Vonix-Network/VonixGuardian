package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.ActionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single-pass parser for the {@code /vg lookup} filter mini-language.
 *
 * <p>The grammar lives in {@code SHARED-CONTRACTS.md} § 4.2; this parser is a
 * direct, recursive-descent / token-stream implementation of it. It does NO
 * regex-based scanning — every token is walked character by character.
 *
 * <p>The parser is stateless and thread-safe; instances are cheap and
 * disposable.
 *
 * <h2>Examples</h2>
 * <pre>
 *   QueryParser p = new QueryParser();
 *   QueryFilter f = p.parse("u:Notch t:1h r:20 a:-block #count",
 *                           new QueryParseContext(0, 64, 0));
 * </pre>
 */
public final class QueryParser {

    /**
     * CoreProtect-compatible {@code a:} token aliases. CP exposes
     * {@code a:login} / {@code a:logout} as friendly synonyms for the join /
     * leave session events; VonixGuardian's canonical tokens are
     * {@code +session} / {@code -session}. The alias rewrite runs BEFORE
     * {@link ActionType#byToken(String)} so the rest of the action-parsing
     * pipeline (including sign detection) sees the canonical form.
     */
    private static final Map<String, String> CP_TOKEN_ALIASES = Map.of(
        "login",  "+session",
        "logout", "-session"
    );

    /**
     * CoreProtect-compatible {@code a:} multi-target aliases. Some CP tokens
     * (notably {@code a:inventory}) cover a sign-pair of canonical action
     * types. Each entry expands to {@link ActionType}s with {@link
     * QueryFilter.ActionSelect.Sign#ANY}.
     */
    private static final Map<String, ActionType[]> CP_MULTI_ALIASES = Map.of(
        "inventory", new ActionType[] {
            ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW
        }
    );

    /**
     * Caller-supplied context for parsing positional tokens.
     *
     * <p>Used by {@code r:<n>} (numeric radius) which must be centered on the
     * issuing player. May be {@code null} when the command originated from the
     * console — in that case any {@code r:<n>} token raises {@link
     * QueryParseException}.
     *
     * @param x  player block X
     * @param y  player block Y
     * @param z  player block Z
     */
    public record QueryParseContext(int x, int y, int z) {}

    /** Parses a raw filter expression.
     *
     * @param raw the user-typed expression; {@code null} or blank yields {@link QueryFilter#empty()}
     * @param ctx caller position for {@code r:<n>} centering; {@code null} for console
     * @return the parsed {@link QueryFilter}
     * @throws QueryParseException if any token is malformed
     */
    public QueryFilter parse(String raw, QueryParseContext ctx) {
        if (raw == null) {
            return QueryFilter.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return QueryFilter.empty();
        }

        QueryFilter.Builder b = QueryFilter.builder();

        // Whitespace tokenization — no regex.
        int i = 0;
        int n = trimmed.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(trimmed.charAt(i))) {
                i++;
            }
            if (i >= n) break;
            int start = i;
            while (i < n && !Character.isWhitespace(trimmed.charAt(i))) {
                i++;
            }
            String tok = trimmed.substring(start, i);
            parseToken(tok, b, ctx);
        }
        return b.build();
    }

    // --- per-token dispatch -------------------------------------------------

    private void parseToken(String tok, QueryFilter.Builder b, QueryParseContext ctx) {
        if (tok.isEmpty()) return;

        // Hash flags come first — they don't share the `prefix:` shape.
        if (tok.charAt(0) == '#') {
            parseHashFlag(tok, b);
            return;
        }

        int colon = tok.indexOf(':');
        if (colon <= 0 || colon == tok.length() - 1) {
            throw bad(tok, "expected '<prefix>:<value>' (u:, t:, r:, a:, i:, e:) or #flag");
        }
        String prefix = tok.substring(0, colon).toLowerCase(Locale.ROOT);
        String value  = tok.substring(colon + 1);
        switch (prefix) {
            case "u" -> parseUserTok(tok, value, b);
            case "t" -> parseTimeTok(tok, value, b);
            case "r" -> parseRadiusTok(tok, value, b, ctx);
            case "a" -> parseActionTok(tok, value, b);
            case "i" -> parseIdentList(tok, value, b, /*include*/ true);
            case "e" -> parseIdentList(tok, value, b, /*include*/ false);
            default  -> throw bad(tok,
                "unknown prefix '" + prefix + ":' — expected one of u: t: r: a: i: e:");
        }
    }

    // --- u: -----------------------------------------------------------------

    private void parseUserTok(String tok, String value, QueryFilter.Builder b) {
        String[] parts = splitComma(value);
        if (parts.length == 0) {
            throw bad(tok, "u: requires at least one player name or #sentinel");
        }
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                throw bad(tok, "u: contains an empty entry — remove the stray comma");
            }
            if (p.charAt(0) == '#') {
                String sentinel = p.toLowerCase(Locale.ROOT);
                b.addUser(new QueryFilter.UserSel(null, sentinel, true));
            } else {
                if (!isValidPlayerName(p)) {
                    throw bad(tok, "'" + p + "' is not a valid player name "
                        + "(letters, digits, underscore; max 16) — did you mean a #sentinel?");
                }
                b.addUser(new QueryFilter.UserSel(null, p, false));
            }
        }
    }

    private static boolean isValidPlayerName(String s) {
        if (s.length() == 0 || s.length() > 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                || (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    // --- t: -----------------------------------------------------------------

    private void parseTimeTok(String tok, String value, QueryFilter.Builder b) {
        if (value.isEmpty()) {
            throw bad(tok, "t: requires a duration like 1h, 2w5d, or 1h-2h");
        }
        int dash = findRangeDash(value);
        long now = System.currentTimeMillis();
        if (dash < 0) {
            long ms = parseDurationMillis(tok, value);
            b.sinceMillis(now - ms);
        } else {
            if (dash == 0 || dash == value.length() - 1) {
                throw bad(tok, "t: range requires durations on both sides of '-' (e.g. 1h-2h)");
            }
            String left  = value.substring(0, dash);
            String right = value.substring(dash + 1);
            long lhs = parseDurationMillis(tok, left);
            long rhs = parseDurationMillis(tok, right);
            // "1h-2h" means "between 2h ago and 1h ago".
            long older  = Math.max(lhs, rhs);
            long newer  = Math.min(lhs, rhs);
            b.sinceMillis(now - older);
            b.untilMillis(now - newer);
        }
    }

    /** Locate the dash that separates two durations, ignoring a leading sign. */
    private static int findRangeDash(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '-') return i;
        }
        return -1;
    }

    /** Single-pass duration tokenizer: ((digit+ ('.' digit+)?) unit)+ — commas between
     *  components are tolerated for CoreProtect parity (e.g. {@code 2w,5d,7h,2m,10s}). */
    private long parseDurationMillis(String tok, String s) {
        long total = 0L;
        int i = 0;
        int n = s.length();
        boolean anyComponent = false;
        while (i < n) {
            // Skip CP-style component separators.
            while (i < n && s.charAt(i) == ',') i++;
            if (i >= n) break;
            int numStart = i;
            while (i < n && (s.charAt(i) >= '0' && s.charAt(i) <= '9')) i++;
            if (i < n && s.charAt(i) == '.') {
                i++;
                while (i < n && (s.charAt(i) >= '0' && s.charAt(i) <= '9')) i++;
            }
            if (i == numStart) {
                throw bad(tok, "duration component must start with a number: '" + s + "'");
            }
            if (i >= n) {
                throw bad(tok, "duration '" + s + "' is missing a unit "
                    + "(use one of s, m, h, d, w)");
            }
            String numStr = s.substring(numStart, i);
            double num;
            try {
                num = Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                throw bad(tok, "duration '" + s + "' has invalid number '" + numStr + "'");
            }
            char unit = Character.toLowerCase(s.charAt(i));
            i++;
            long unitMs = switch (unit) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default  -> throw bad(tok,
                    "unknown duration unit '" + unit + "' in '" + s + "' — use s, m, h, d, or w");
            };
            total += (long) (num * unitMs);
            anyComponent = true;
        }
        if (!anyComponent) {
            throw bad(tok, "empty duration");
        }
        // Clamp: a caller-typed duration that rounds down to less than 1 second
        // (e.g. `t:0.001s`) is almost certainly a mistake but should not silently
        // become "0 ms ago = now". Clamp to 1 second for CoreProtect parity.
        if (total < 1_000L) {
            total = 1_000L;
        }
        return total;
    }

    // --- r: -----------------------------------------------------------------

    private void parseRadiusTok(String tok, String value, QueryFilter.Builder b, QueryParseContext ctx) {
        if (value.isEmpty()) {
            throw bad(tok, "r: requires a radius (e.g. r:10, r:#global, r:#world_nether)");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.charAt(0) == '#') {
            switch (lower) {
                case "#global" -> {
                    b.radius(-1);
                    b.worldSel(new QueryFilter.WorldSel(null, true));
                }
                case "#we", "#worldedit" -> {
                    // The contract record has no WorldEdit-selection slot yet; we accept
                    // the token but leave radius/world unset. See package summary for
                    // the open contract concern. The lookup engine is expected to read
                    // the player's WorldEdit region directly when neither r: nor a
                    // world selector is present and a WE selection exists.
                }
                // CoreProtect-parity dimension shorthands. Map to vanilla
                // canonical world keys so DAO row matches (rows are stored with
                // the full {@code minecraft:<path>} form).
                case "#nether"    -> b.worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false));
                case "#overworld" -> b.worldSel(new QueryFilter.WorldSel("minecraft:overworld",  false));
                case "#end"       -> b.worldSel(new QueryFilter.WorldSel("minecraft:the_end",    false));
                default -> {
                    if (lower.startsWith("#world_")) {
                        String key = value.substring("#world_".length());
                        if (key.isEmpty()) {
                            throw bad(tok, "r:#world_<key> requires a world key after '#world_'");
                        }
                        // Allow shorthand: "nether" -> "minecraft:the_nether" is NOT done here;
                        // the parser keeps the raw key. The lookup engine resolves shorthands.
                        b.worldSel(new QueryFilter.WorldSel(key, false));
                    } else {
                        throw bad(tok, "unknown radius keyword '" + value
                            + "' — expected r:<n>, r:#global, r:#world_<key>, r:#we, or r:#worldedit");
                    }
                }
            }
            return;
        }
        // Numeric radius.
        int r;
        try {
            r = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw bad(tok, "radius must be a non-negative integer or a #keyword, got '" + value + "'");
        }
        if (r < 0) {
            throw bad(tok, "radius must be >= 0 (got " + r + ") — use r:#global for unbounded");
        }
        if (ctx == null) {
            throw bad(tok, "r:<n> requires a player position; console must use r:#global "
                + "or r:#world_<key>");
        }
        b.radius(r);
        b.center(ctx.x(), ctx.y(), ctx.z());
    }

    // --- a: -----------------------------------------------------------------

    private void parseActionTok(String tok, String value, QueryFilter.Builder b) {
        if (value.isEmpty()) {
            throw bad(tok, "a: requires an action name — " + actionSuggestion());
        }
        String lower = value.toLowerCase(Locale.ROOT);
        // 0a) CoreProtect-parity multi-target alias (e.g. a:inventory expands to
        //     INVENTORY_DEPOSIT + INVENTORY_WITHDRAW with Sign.ANY).
        ActionType[] multi = CP_MULTI_ALIASES.get(lower);
        if (multi != null) {
            for (ActionType t : multi) {
                b.addAction(new QueryFilter.ActionSelect(t, QueryFilter.ActionSelect.Sign.ANY));
            }
            return;
        }
        // 0b) CoreProtect-parity scalar alias rewrite (login -> +session, logout -> -session).
        //     Applied BEFORE the byToken lookup so sign-detection sees the canonical form.
        String aliased = CP_TOKEN_ALIASES.get(lower);
        if (aliased != null) {
            lower = aliased;
        }
        // 1) exact token match (handles `a:+block`, `a:-container`, `a:kill`, ...)
        try {
            ActionType direct = ActionType.byToken(lower);
            QueryFilter.ActionSelect.Sign sign;
            char first = lower.charAt(0);
            if (first == '+') sign = QueryFilter.ActionSelect.Sign.PLACE_ONLY;
            else if (first == '-') sign = QueryFilter.ActionSelect.Sign.BREAK_ONLY;
            else sign = QueryFilter.ActionSelect.Sign.ANY;
            b.addAction(new QueryFilter.ActionSelect(direct, sign));
            return;
        } catch (IllegalArgumentException ignored) {
            // fall through to family handling
        }
        // 2) bare family (e.g. `a:block`) — expand to ALL ActionTypes in the
        //    matching Category with Sign.ANY. Iterates Category.values() so
        //    new categories Just Work as they are added to ActionType.
        for (ActionType.Category cat : ActionType.Category.values()) {
            if (cat.name().toLowerCase(Locale.ROOT).equals(lower)) {
                for (ActionType t : ActionType.family(cat)) {
                    b.addAction(new QueryFilter.ActionSelect(t, QueryFilter.ActionSelect.Sign.ANY));
                }
                return;
            }
        }
        throw bad(tok, "unknown action '" + value + "' — " + actionSuggestion());
    }

    private static String actionSuggestion() {
        String tokens = Arrays.stream(ActionType.values())
            .map(ActionType::token)
            .collect(Collectors.joining(", "));
        String families = Arrays.stream(ActionType.Category.values())
            .map(c -> c.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));
        return "valid actions: " + tokens + " (or families: " + families + ")";
    }

    // --- i: / e: ------------------------------------------------------------

    private void parseIdentList(String tok, String value, QueryFilter.Builder b, boolean include) {
        String[] parts = splitComma(value);
        if (parts.length == 0) {
            throw bad(tok, (include ? "i:" : "e:") + " requires at least one identifier");
        }
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                throw bad(tok, (include ? "i:" : "e:")
                    + " contains an empty entry — remove the stray comma");
            }
            String id = qualifyId(tok, p);
            if (include) b.addInclude(id); else b.addExclude(id);
        }
    }

    /** Defaults namespace to {@code minecraft:} if absent; validates the identifier. */
    private String qualifyId(String tok, String p) {
        int colon = p.indexOf(':');
        String namespace;
        String path;
        if (colon < 0) {
            namespace = "minecraft";
            path = p;
        } else {
            namespace = p.substring(0, colon);
            path = p.substring(colon + 1);
        }
        if (namespace.isEmpty() || !isValidIdComponent(namespace)) {
            throw bad(tok, "invalid namespace '" + namespace + "' in identifier '" + p + "'");
        }
        if (path.isEmpty() || !isValidIdComponent(path)) {
            throw bad(tok, "invalid identifier path '" + path + "' in '" + p + "'");
        }
        return (namespace + ":" + path).toLowerCase(Locale.ROOT);
    }

    private static boolean isValidIdComponent(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) return false;
        }
        return true;
    }

    // --- #flag --------------------------------------------------------------

    private void parseHashFlag(String tok, QueryFilter.Builder b) {
        String name = tok.substring(1).toLowerCase(Locale.ROOT);
        switch (name) {
            case "preview" -> b.preview(true);
            case "count"   -> b.countOnly(true);
            case "verbose" -> b.verbose(true);
            case "silent"  -> b.silent(true);
            case "optimize" -> b.optimize(true);
            default -> throw bad(tok,
                "unknown flag '" + tok + "' — valid flags: #preview, #count, #verbose, #silent, #optimize");
        }
    }

    // --- helpers ------------------------------------------------------------

    private static String[] splitComma(String s) {
        if (s.isEmpty()) return new String[0];
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ',') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out.toArray(new String[0]);
    }

    private static QueryParseException bad(String tok, String msg) {
        return new QueryParseException(tok, "bad token '" + tok + "': " + msg);
    }
}
