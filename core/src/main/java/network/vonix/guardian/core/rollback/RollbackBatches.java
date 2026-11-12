package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.storage.GuardianDao;

import java.util.ArrayList;
import java.util.List;

/**
 * Read and close an unfinished rollback batch.
 *
 * <p>Closing marks the audit row finished. It does not replay, undo, or
 * otherwise change the world, and it does not delete the logged actions.</p>
 */
public final class RollbackBatches {

    /** Action ids printed by {@code /vg batch show} before the remainder is counted. */
    public static final int ID_SAMPLE = 12;

    private RollbackBatches() {}

    /**
     * One boot line when a previous rollback or restore was left open, or no
     * lines when there is nothing to report. Never one line per action.
     */
    public static List<String> recoveryNotice(List<GuardianDao.OpenRollbackBatch> open,
                                               List<Long> actionIds) {
        List<GuardianDao.OpenRollbackBatch> batches = open == null ? List.of() : open;
        int actions = actionIds == null ? 0 : actionIds.size();
        if (batches.isEmpty() && actions == 0) {
            return List.of();
        }
        String batchPart;
        if (batches.isEmpty()) {
            batchPart = "no open batch row";
        } else {
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < batches.size(); i++) {
                if (i > 0) ids.append(", ");
                ids.append(batches.get(i).id());
            }
            String count = batches.size() == 20
                ? "at least 20 shown"
                : Integer.toString(batches.size());
            batchPart = count + " (ids " + ids + ")";
        }
        return List.of("Open rollback batches: " + batchPart
            + " covering " + actions + " " + (actions == 1 ? "action" : "actions")
            + ". Use /vg batch to inspect or close them. The world was not changed.");
    }

    public static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static List<String> list(GuardianDao dao) throws Exception {
        List<GuardianDao.OpenRollbackBatch> open = dao.findOpenRollbackBatches(20);
        if (open == null || open.isEmpty()) {
            return List.of("[VonixGuardian] No open rollback batches. The world was not changed.");
        }
        List<String> lines = new ArrayList<>();
        String heading = open.size() == 20
            ? "At least 20 open rollback batches. More may exist."
            : open.size() + " open rollback " + (open.size() == 1 ? "batch" : "batches") + ".";
        lines.add("[VonixGuardian] " + heading + " The world was not changed.");
        for (GuardianDao.OpenRollbackBatch batch : open) {
            lines.add("  " + batch.id() + " " + modeName(batch.mode())
                + ", " + batch.affected() + " " + (batch.affected() == 1 ? "action" : "actions"));
        }
        lines.add("  /vg batch show <id> lists the action ids. /vg batch close <id> marks one finished and does not change the world.");
        return List.copyOf(lines);
    }

    public static List<String> show(GuardianDao dao, long batchId) throws Exception {
        GuardianDao.RollbackBatch batch = dao.findRollbackBatch(batchId);
        if (batch == null) {
            return List.of("[VonixGuardian] No batch " + batchId
                + ". The world was not changed.");
        }
        List<Long> ids = dao.findRollbackBatchActionIds(batchId);
        if (ids == null) ids = List.of();
        List<String> lines = new ArrayList<>();
        String state = batch.open() ? "open" : "already closed";
        lines.add("[VonixGuardian] Batch " + batch.id() + " is " + state + " ("
            + modeName(batch.mode()) + ", " + ids.size() + " "
            + (ids.size() == 1 ? "action" : "actions")
            + "). The world was not changed.");
        lines.add("  actions: " + formatIds(ids));
        if (batch.open()) {
            lines.add("  /vg batch close " + batch.id()
                + " marks this audit row finished and does not change the world.");
        }
        return List.copyOf(lines);
    }

    /**
     * Mark one open batch finished. Uses {@link GuardianDao#closeOpenRollbackBatch}
     * so an already-closed row is left alone. Does not call
     * {@link GuardianDao#closeRollbackBatch(long)} and does not touch the world.
     */
    public static List<String> close(GuardianDao dao, long batchId) throws Exception {
        GuardianDao.RollbackBatch before = dao.findRollbackBatch(batchId);
        if (before == null) {
            return List.of("[VonixGuardian] No batch " + batchId
                + ". Nothing was closed and the world was not changed.");
        }
        if (!before.open()) {
            return List.of("[VonixGuardian] Batch " + batchId + " is already closed ("
                + modeName(before.mode()) + ", " + before.affected()
                + " actions). The world was not changed.");
        }
        int updated = dao.closeOpenRollbackBatch(batchId);
        if (updated != 1) {
            return List.of("[VonixGuardian] Batch " + batchId
                + " was not closed. The world was not changed.");
        }
        return List.of("[VonixGuardian] Batch " + batchId + " is now closed ("
            + modeName(before.mode()) + ", " + before.affected()
            + " actions). The world was not changed. The logged actions are still in the audit log.");
    }

    static String formatIds(List<Long> ids) {
        if (ids.isEmpty()) return "(none)";
        int shown = Math.min(ID_SAMPLE, ids.size());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            out.append(ids.get(i));
        }
        int hidden = ids.size() - shown;
        if (hidden > 0) {
            out.append(", and ").append(hidden).append(" more");
        }
        return out.toString();
    }

    static String modeName(int mode) {
        return switch (mode) {
            case 0 -> "rollback";
            case 1 -> "restore";
            default -> "mode " + mode;
        };
    }
}
