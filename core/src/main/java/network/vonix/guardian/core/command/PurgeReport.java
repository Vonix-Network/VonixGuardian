package network.vonix.guardian.core.command;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.rollback.PurgeEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator-facing lines for {@code /vg purge}. Dry runs say that nothing was
 * deleted. A real purge says how many rows were removed.
 */
public final class PurgeReport {

    private PurgeReport() {}

    public static List<String> lines(PurgeEngine.PurgeResult result) {
        if (result == null) {
            return List.of("[VonixGuardian] Purge produced no result.");
        }
        return switch (result.mode()) {
            case COUNT -> List.of("[VonixGuardian] Purge count: " + result.matchedCount()
                + " rows match. Nothing was deleted.");
            case PREVIEW -> previewLines(result);
            case DELETE -> List.of("[VonixGuardian] Purge removed " + result.deletedCount()
                + " rows (minAge=" + result.minAgeSeconds() + "s).");
        };
    }

    private static List<String> previewLines(PurgeEngine.PurgeResult result) {
        List<String> lines = new ArrayList<>();
        lines.add("[VonixGuardian] Purge preview: " + result.matchedCount()
            + " rows would be deleted. Nothing was deleted.");
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
