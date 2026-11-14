package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code actions.actionTypeBlacklist} silences individual action types without
 * turning off their whole category.
 *
 * <p>Before 2.3.0 the only controls were per-category toggles, so muting one
 * noisy type (explosions in a farm area, fluid flow in an ocean build) meant
 * losing every block edit in that category too.</p>
 */
class ActionTypeBlacklistTest {

    private static GuardianConfig.Actions withBlacklist(List<String> names) {
        return new GuardianConfig.Actions(
            true, true, true, true, true, true, true, true, true, true, true,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            0L, 0, List.of(), false,
            true, true, true, true, true, true, false, false,
            true, true, false, true, false,
            true, names);
    }

    private static boolean logs(GuardianConfig.Actions cfg, ActionType t) {
        return EventGate.logsType(cfg, t);
    }

    @Test
    void emptyBlacklistLogsEverything() {
        GuardianConfig.Actions cfg = withBlacklist(List.of());
        assertTrue(logs(cfg, ActionType.EXPLOSION));
        assertTrue(logs(cfg, ActionType.FLUID_FLOW));
        assertTrue(logs(cfg, ActionType.BLOCK_PLACE));
    }

    @Test
    void blacklistedTypeIsNotLoggedButItsCategorySurvives() {
        GuardianConfig.Actions cfg = withBlacklist(List.of("EXPLOSION"));

        assertFalse(logs(cfg, ActionType.EXPLOSION),
            "blacklisted type must not be logged");
        // The point of the feature: the rest of the WORLD category still logs.
        assertTrue(logs(cfg, ActionType.BLOCK_PLACE),
            "blacklisting one type must not disable the category");
        assertTrue(logs(cfg, ActionType.FLUID_FLOW),
            "sibling types in the same category keep logging");
    }

    @Test
    void matchingIsCaseInsensitiveAndTrimmed() {
        GuardianConfig.Actions cfg = withBlacklist(List.of("  explosion ", "fluid_flow"));
        assertFalse(logs(cfg, ActionType.EXPLOSION));
        assertFalse(logs(cfg, ActionType.FLUID_FLOW));
    }

    @Test
    void unknownNamesAreIgnoredRatherThanBreakingBoot() {
        GuardianConfig.Actions cfg = withBlacklist(List.of("NOT_A_REAL_TYPE"));
        // A typo must not throw and must not silence anything.
        assertTrue(logs(cfg, ActionType.BLOCK_PLACE));
        assertTrue(logs(cfg, ActionType.EXPLOSION));
    }

    /** The with* helpers must not silently drop the blacklist — that was a real bug. */
    @Test
    void withHelpersPreserveTheBlacklist() {
        GuardianConfig.Actions cfg = withBlacklist(List.of("EXPLOSION"));
        GuardianConfig.Actions afterItem = cfg.withItemBlacklist(List.of("minecraft:dirt"));
        assertFalse(logs(afterItem, ActionType.EXPLOSION),
            "changing the item blacklist must not resurrect a silenced action type");
    }
}
