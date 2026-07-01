/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.query;

import java.util.Locale;

import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the invariant that {@link ActionTokens#ALL} is exactly the set of
 * tokens {@link QueryParser}'s {@code a:} accepts — no rejects, no missing
 * canonical entries. Loader-cell {@code GuardianSuggestions} arrays are
 * populated from this list, so a drift here would surface as tab-complete
 * suggestions that fail parsing.
 */
class ActionTokenParityTest {

    @Test
    void everyTokenParses() {
        QueryParser parser = new QueryParser();
        for (String tok : ActionTokens.ALL) {
            assertDoesNotThrow(
                () -> parser.parse("a:" + tok, null),
                "ActionTokens.ALL entry '" + tok + "' failed to parse as a:" + tok);
        }
    }

    @Test
    void everyActionTypeTokenIsAdvertised() {
        for (ActionType at : ActionType.values()) {
            String t = at.token();
            assertTrue(
                ActionTokens.ALL.contains(t),
                "ActionType." + at.name() + " token '" + t + "' missing from ActionTokens.ALL");
        }
    }

    @Test
    void everyCategoryUmbrellaIsAdvertised() {
        for (ActionType.Category cat : ActionType.Category.values()) {
            String name = cat.name().toLowerCase(Locale.ROOT);
            assertTrue(
                ActionTokens.ALL.contains(name),
                "Category." + cat.name() + " umbrella '" + name + "' missing from ActionTokens.ALL");
        }
    }

    @Test
    void cpAliasesArePresent() {
        assertTrue(ActionTokens.ALL.contains("login"),  "CP alias 'login' missing");
        assertTrue(ActionTokens.ALL.contains("logout"), "CP alias 'logout' missing");
        assertTrue(ActionTokens.ALL.contains("inventory"), "CP multi-alias 'inventory' missing");
    }
}
