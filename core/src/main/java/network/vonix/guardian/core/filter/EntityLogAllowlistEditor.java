/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.filter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pure, loader-agnostic edit logic behind the {@code /vg entitylog add|remove|list}
 * command. Kept in {@code core} so the eight loader cells share one tested
 * implementation and the Brigadier layer in each cell stays a thin adapter.
 *
 * <p>An entitylog entry is an entry accepted by {@link VanillaGrieferSet#matches}:
 * an exact registry key ({@code isleofberk:night_fury}), a namespace wildcard
 * ({@code isleofberk:*}), or a bare namespace ({@code isleofberk}). Entries are
 * stored verbatim (trimmed) so {@code list} round-trips exactly what the operator
 * typed and {@link VanillaGrieferSet#matches} interprets the shape at match time.</p>
 *
 * <p>All methods are null-safe and never mutate their inputs; add/remove return a
 * fresh immutable list. Duplicate entries are collapsed case-sensitively but
 * insertion order is preserved ({@link LinkedHashSet}) so {@code list} output is
 * stable across restarts.</p>
 */
public final class EntityLogAllowlistEditor {

    private EntityLogAllowlistEditor() {
        // utility
    }

    /** Outcome of an {@link #add}/{@link #remove} edit. */
    public record Result(List<String> allowlist, boolean changed, String message) {}

    /**
     * Normalise a raw operator token into canonical entitylog-entry form:
     * trimmed, namespace/path lower-cased (Minecraft resource locations are
     * lower-case by contract), preserving the {@code :*} wildcard and bare-namespace
     * shapes. Returns {@code null} for null/blank/structurally-invalid input.
     *
     * <p>Rejected: empty, whitespace-only, a lone {@code *} or {@code :*} (no
     * namespace), and keys containing whitespace. This is deliberately conservative
     * — a malformed entry that silently never matches is worse than an up-front
     * error the operator can correct.</p>
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.indexOf(' ') >= 0 || t.indexOf('\t') >= 0) return null;
        String lower = t.toLowerCase(Locale.ROOT);

        // Namespace wildcard "ns:*" — namespace must be non-empty.
        if (lower.endsWith(":*")) {
            String ns = lower.substring(0, lower.length() - 2);
            return ns.isEmpty() ? null : ns + ":*";
        }
        // A lone "*" is not a valid entry (would be an unbounded flood re-open).
        if (lower.equals("*")) return null;

        // Exact key "ns:path" — both sides non-empty, single colon.
        int colon = lower.indexOf(':');
        if (colon >= 0) {
            if (colon == 0 || colon == lower.length() - 1) return null;
            if (lower.indexOf(':', colon + 1) >= 0) return null; // no second colon
            return lower;
        }
        // Bare namespace "ns" — treated as "ns:*" by VanillaGrieferSet.matches.
        return lower;
    }

    /** Defensive immutable copy of the current allowlist ({@code null} → empty). */
    public static List<String> currentOrEmpty(List<String> current) {
        return current == null ? List.of() : List.copyOf(current);
    }

    /**
     * Add {@code raw} to {@code current}. Idempotent: adding an entry already
     * present is a no-op reported via {@link Result#changed()}.
     */
    public static Result add(List<String> current, String raw) {
        String entry = normalize(raw);
        if (entry == null) {
            return new Result(currentOrEmpty(current), false,
                    "Invalid entity entry: '" + raw + "'. Use ns:path, ns:*, or ns.");
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(currentOrEmpty(current));
        if (!set.add(entry)) {
            return new Result(List.copyOf(set), false,
                    "Already whitelisted: " + entry);
        }
        return new Result(List.copyOf(set), true, "Added " + entry);
    }

    /**
     * Remove {@code raw} from {@code current}. The token is normalised the same
     * way as {@link #add} so operators remove entries with the exact syntax they
     * added them.
     */
    public static Result remove(List<String> current, String raw) {
        String entry = normalize(raw);
        if (entry == null) {
            return new Result(currentOrEmpty(current), false,
                    "Invalid entity entry: '" + raw + "'. Use ns:path, ns:*, or ns.");
        }
        List<String> next = new ArrayList<>(currentOrEmpty(current));
        boolean removed = next.remove(entry);
        if (!removed) {
            return new Result(currentOrEmpty(current), false,
                    "Not whitelisted: " + entry);
        }
        return new Result(List.copyOf(next), true, "Removed " + entry);
    }
}
