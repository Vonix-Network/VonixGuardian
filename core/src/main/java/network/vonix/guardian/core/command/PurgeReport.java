package network.vonix.guardian.core.command;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.PurgeEngine;
import network.vonix.guardian.core.rollback.PurgeScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator-facing lines for {@code /vg purge}. Dry runs say that nothing was
 * deleted. A real purge says how many rows were removed.
 */
public final class PurgeReport {

    private PurgeReport() {}

    public static List<String> lines(PurgeEngine.PurgeResult result) {
        return lines(result, null);
    }

    public static List<String> lines(PurgeEngine.PurgeResult result, QueryFilter filter) {
        if (result == null) {
            return List.of("[VonixGuardian] Purge produced no result.");
        }
        String scope = PurgeScope.label(filter);
        return switch (result.mode()) {
            case COUNT -> List.of("[VonixGuardian] Purge count: " + result.matchedCount()
                + " rows match in " + scope + ". Nothing was deleted.");
            case PREVIEW -> previewLines(result, scope);
            case DELETE -> List.of("[VonixGuardian] Purge removed " + result.deletedCount()
                + " rows in " + scope + " (minAge=" + result.minAgeSeconds() + "s).");
        };
    }

    private static List<String> previewLines(PurgeEngine.PurgeResult result, String scope) {
        List<String> lines = new ArrayList<>();
        lines.add("[VonixGuardian] Purge preview: " + result.matchedCount()
            + " rows would be deleted in " + scope + ". Nothing was deleted.");
        List<Action> sample = result.sample();
        int shown = 0;
        for (Action action : sample) {
            if (shown >= PurgeEngine.PREVIEW_SAMPLE_LIMIT) break;
            lines.add("  " + sampleLine(action));
            shown++;
        }
        long hidden = result.matchedCount() - shown;
        if (hidden > 0) {
            lines.add("  … and " + hidden + " more.");
        }
        return List.copyOf(lines);
    }

    static String sampleLine(Action action) {
        String world = action.worldId() == null ? "?" : action.worldId();
        String target = action.targetId() == null ? "?" : action.targetId();
        String who = action.actorName() == null ? "?" : action.actorName();
        return world + " " + action.x() + "," + action.y() + "," + action.z()
            + " " + target + " by " + who;
    }
}
