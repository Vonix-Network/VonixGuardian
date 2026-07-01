/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import network.vonix.guardian.core.action.ActionType;

/**
 * Canonical, parser-verified list of tokens accepted by {@code a:}.
 *
 * <p>Sources, in the following stable order suitable for tab-complete:
 * <ol>
 *   <li>every {@link ActionType.Category} name lowercased — the bare-family
 *       umbrellas ({@code block}, {@code container}, {@code item},
 *       {@code entity}, {@code world}, {@code message}, {@code session},
 *       {@code interact}) that {@link QueryParser#parseActionTok} matches
 *       in its family-fallback branch;</li>
 *   <li>every {@link ActionType#token()} — all 39 canonical action tokens
 *       looked up by {@link ActionType#byToken(String)};</li>
 *   <li>CoreProtect-parity aliases ({@code login}, {@code logout}, and
 *       {@code inventory}) — see {@code QueryParser.CP_TOKEN_ALIASES} and
 *       {@code QueryParser.CP_MULTI_ALIASES}. Keep these in sync with those
 *       constants; {@code ActionTokenParityTest} enforces the invariant.</li>
 * </ol>
 *
 * <p>Both {@link QueryParser#parseActionTok} and the loader-cell
 * {@code GuardianSuggestions.ACTIONS} array must reference this list — every
 * entry here MUST parse cleanly, and no accepted token may be missing.
 */
public final class ActionTokens {

    /** Everything a: accepts, in a stable order suitable for tab-complete. */
    public static final List<String> ALL = buildList();

    private ActionTokens() {
        // constants holder
    }

    private static List<String> buildList() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        // 1) Category umbrellas.
        for (ActionType.Category cat : ActionType.Category.values()) {
            out.add(cat.name().toLowerCase(Locale.ROOT));
        }
        // 2) Every ActionType token.
        for (ActionType at : ActionType.values()) {
            out.add(at.token());
        }
        // 3) CP-parity aliases. Keep in sync with QueryParser.CP_TOKEN_ALIASES
        //    and QueryParser.CP_MULTI_ALIASES.
        out.add("login");
        out.add("logout");
        out.add("inventory");
        return Collections.unmodifiableList(new ArrayList<>(out));
    }
}
