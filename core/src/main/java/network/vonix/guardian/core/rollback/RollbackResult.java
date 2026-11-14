package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.query.QueryFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a {@link RollbackEngine#rollback} or {@link RollbackEngine#restore}
 * invocation. Returned to the caller (and pushed onto the per-actor
 * {@link UndoStack} when applicable).
 *
 * <p>W2-01 refactor (v1.1.6): now carries the {@link #originalFilter()} that
 * built the plan. This lets {@code /vg undo} pop an entry and call the
 * <em>inverse</em> operation ({@link RollbackEngine#restore} after a rollback,
 * or {@link RollbackEngine#rollback} after a restore) on exactly the same
 * action set, without having to reconstruct the filter from user text.</p>
 *
 * <p>2.1.1: every execute path returns one authoritative {@link Status}. Mixed
 * world outcomes, compensation, repair-required, and persistence failure after
 * world success are never collapsed into a generic exception.</p>
 *
 * @param actorUuid            who triggered the operation; may be {@code null} for console
 * @param mode                 {@link Mode#ROLLBACK} or {@link Mode#RESTORE}
 * @param preview              {@code true} when no mutations were dispatched
 * @param affectedIds          action IDs that were (or would be, if preview) toggled
 * @param skippedIds           action IDs excluded from the plan or skipped at dispatch
 * @param plannedSteps         number of world mutations the plan would dispatch
 * @param dispatchedSteps      number of world mutations confirmed after executor completion
 *                             ({@code 0} for previews)
 * @param originalFilter       the (rolledBack-normalized) filter the plan was built
 *                             from; may be {@code null} for legacy callers that
 *                             used the pre-v1.1.6 7-arg constructor
 * @param status               authoritative terminal status for this request
 * @param batchId              {@code vg_rollback_batches.id}, or {@code 0} when no batch opened
 * @param failedCount          world mutations that failed (not skipped, not compensated)
 * @param compensatedCount     pair halves that were successfully compensated after mate failure
 * @param repairRequiredCount  pair halves persisted to {@code vg_repair_required}
 * @param batchClosed          {@code true} only when {@code closeRollbackBatch} updated exactly one row
 * @param skippedByReason      plan-time and dispatch-time skips split by {@link SkipReason};
 *                             totals always agree with {@link #skippedCount()}
 */
public record RollbackResult(
    UUID actorUuid,
    Mode mode,
    boolean preview,
    List<Long> affectedIds,
    List<Long> skippedIds,
    int plannedSteps,
    int dispatchedSteps,
    QueryFilter originalFilter,
    Status status,
    long batchId,
    int failedCount,
    int compensatedCount,
    int repairRequiredCount,
    boolean batchClosed,
    Map<SkipReason, Integer> skippedByReason,
    /**
     * Where a preview would have touched the world, for a client to draw.
     * Empty for a non-preview result or when the plan had nothing to dispatch.
     */
    PreviewOutline previewOutline
) {

    /**
     * Why one planned or dispatched row was not applied.
     *
     * <p>An operator must be able to reconcile {@code skipped=N} against what
     * they asked for. A single collapsed number cannot say whether the rows
     * were never reversible, deduplicated by design, refused by the loader, or
     * corrupt. Each constant is a distinct operator action:</p>
     *
     * <ul>
     *   <li>{@link #AUDIT_ONLY} — nothing to undo; the row never claimed to be
     *       a world change (chat, command, sign, session, username, click).</li>
     *   <li>{@link #UNTRACKED_STATE} — a real world change whose prior state
     *       this build does not record, so inverting it would guess
     *       (dispense, piston, form, spread, craft, spawn, chunk populate).</li>
     *   <li>{@link #SUPERSEDED_POSITION} — an older row at a block position a
     *       newer row already restores; applying both would be wrong.</li>
     *   <li>{@link #UNSUPPORTED_ENTITY} — the entity restore path cannot run
     *       on this server (for example restoring a kill in restore mode).</li>
     *   <li>{@link #MALFORMED_PAYLOAD} — the row's stored payload does not
     *       parse, so the mutation was never attempted.</li>
     * </ul>
     *
     * <p>Genuine mutator failures are <em>not</em> skips: they surface as
     * {@link #failedCount()} and give the action to the operator as a retry or
     * repair item.</p>
     */
    public enum SkipReason {
        /** No world state was ever recorded for this row type. */
        AUDIT_ONLY,
        /**
         * A block or entity interaction. The click itself changes nothing that
         * can be inverted — the effect it caused is recorded as its own row.
         */
        INTERACTION_ONLY,
        /** A world change whose prior state is not tracked, so it cannot be inverted. */
        UNTRACKED_STATE,
        /** An older row at a position a newer planned row already restores. */
        SUPERSEDED_POSITION,
        /** The entity restore path is unsupported for this row in this mode. */
        UNSUPPORTED_ENTITY,
        /** The row's stored payload does not parse; nothing was attempted. */
        MALFORMED_PAYLOAD,
        /**
         * An inventory row with no item NBT.
         *
         * <p>Distinct from {@link #MALFORMED_PAYLOAD} because it has a different
         * cause and a different fix: the payload was never captured, because
         * {@code storage.persistNbt} was {@code false} when the row was written.
         * Reporting it as an unreadable payload sent operators looking for corruption
         * when the real answer was one config key.</p>
         */
        MISSING_ITEM_IDENTITY,
        /**
         * An inventory row with no actor UUID, so the mutation has no target.
         *
         * <p>Also not corruption: the row cannot be aimed at anyone, so it was never a
         * valid request.</p>
         */
        MISSING_ACTOR
    }

    /** Operation kind. */
    public enum Mode { ROLLBACK, RESTORE }

    /**
     * Authoritative terminal status for one rollback/restore/undo request.
     * Command adapters must print this status; they must not invent a later
     * generic failure for the same request.
     */
    public enum Status {
        SUCCESS,
        PARTIAL,
        FAILED,
        COMPENSATED,
        REPAIR_REQUIRED
    }

    public RollbackResult {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        affectedIds = affectedIds == null ? List.of() : List.copyOf(affectedIds);
        skippedIds = skippedIds == null ? List.of() : List.copyOf(skippedIds);
        skippedByReason = freezeReasons(skippedByReason);
        previewOutline = previewOutline == null ? PreviewOutline.empty() : previewOutline;
        if (plannedSteps < 0) {
            throw new IllegalArgumentException("plannedSteps < 0");
        }
        if (dispatchedSteps < 0) {
            throw new IllegalArgumentException("dispatchedSteps < 0");
        }
        if (failedCount < 0 || compensatedCount < 0 || repairRequiredCount < 0) {
            throw new IllegalArgumentException("counts < 0");
        }
        if (batchId < 0L) {
            throw new IllegalArgumentException("batchId < 0");
        }
        int reasonTotal = 0;
        for (int n : skippedByReason.values()) {
            reasonTotal += n;
        }
        if (reasonTotal > skippedIds.size()) {
            throw new IllegalArgumentException("skippedByReason totals " + reasonTotal
                + " but only " + skippedIds.size() + " skipped id(s) are present");
        }
    }

    /**
     * Defensive copy of a reason histogram: null and non-positive entries drop
     * out so {@code skipped} text never prints a zero bucket, and callers cannot
     * mutate the result after construction.
     */
    private static Map<SkipReason, Integer> freezeReasons(Map<SkipReason, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return Map.of();
        }
        Map<SkipReason, Integer> copy = new EnumMap<>(SkipReason.class);
        for (Map.Entry<SkipReason, Integer> e : counts.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            copy.put(e.getKey(), e.getValue());
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }

    /**
     * 2.2.x-compatible constructor that omits the per-reason skip histogram.
     * Callers that predate {@link SkipReason} cannot invent a split, so the
     * histogram is left empty and {@link #skipReasonDetail()} reports the
     * unclassified remainder explicitly rather than pretending it is zero.
     */
    public RollbackResult(UUID actorUuid,
                          Mode mode,
                          boolean preview,
                          List<Long> affectedIds,
                          List<Long> skippedIds,
                          int plannedSteps,
                          int dispatchedSteps,
                          QueryFilter originalFilter,
                          Status status,
                          long batchId,
                          int failedCount,
                          int compensatedCount,
                          int repairRequiredCount,
                          boolean batchClosed) {
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps,
            originalFilter, status, batchId, failedCount, compensatedCount, repairRequiredCount,
            batchClosed, Map.of(), PreviewOutline.empty());
    }

    /**
     * Constructor for callers that supply the skip histogram but predate
     * {@link #previewOutline()}. A preview outline is only meaningful for a
     * preview, and a caller that cannot describe one simply gets an empty
     * outline rather than an invented one.
     */
    public RollbackResult(UUID actorUuid,
                          Mode mode,
                          boolean preview,
                          List<Long> affectedIds,
                          List<Long> skippedIds,
                          int plannedSteps,
                          int dispatchedSteps,
                          QueryFilter originalFilter,
                          Status status,
                          long batchId,
                          int failedCount,
                          int compensatedCount,
                          int repairRequiredCount,
                          boolean batchClosed,
                          Map<SkipReason, Integer> skippedByReason) {
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps,
            originalFilter, status, batchId, failedCount, compensatedCount, repairRequiredCount,
            batchClosed, skippedByReason, PreviewOutline.empty());
    }

    /**
     * Backwards-compatible constructor that omits {@link #originalFilter()}.
     * Kept for pre-v1.1.6 call sites (tests + cell code before Undo rewires).
     */
    public RollbackResult(UUID actorUuid,
                          Mode mode,
                          boolean preview,
                          List<Long> affectedIds,
                          List<Long> skippedIds,
                          int plannedSteps,
                          int dispatchedSteps) {
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps,
            null, Status.SUCCESS, 0L, 0, 0, 0, true, Map.of());
    }

    /**
     * Pre-2.1.1 constructor. Defaults status to {@link Status#SUCCESS} with a
     * closed batch and zero failure/compensation/repair counts.
     */
    public RollbackResult(UUID actorUuid,
                          Mode mode,
                          boolean preview,
                          List<Long> affectedIds,
                          List<Long> skippedIds,
                          int plannedSteps,
                          int dispatchedSteps,
                          QueryFilter originalFilter) {
        this(actorUuid, mode, preview, affectedIds, skippedIds, plannedSteps, dispatchedSteps,
            originalFilter, Status.SUCCESS, 0L, 0, 0, 0, true);
    }

    /** Total actions touched (rolled back or restored). */
    public int affectedCount() {
        return affectedIds.size();
    }

    /** Dispatch-time plus plan-time skips. */
    public int skippedCount() {
        return skippedIds.size();
    }

    /**
     * Skips that carry no {@link SkipReason} — the compatibility constructors
     * pre-date the histogram. Always {@code 0} on an engine-produced result.
     * Reported separately so an unclassified bucket can never be mistaken for
     * "nothing was skipped".
     */
    public int unclassifiedSkippedCount() {
        int classified = 0;
        for (int n : skippedByReason.values()) {
            classified += n;
        }
        return Math.max(0, skippedIds.size() - classified);
    }

    /** {@code true} when every skipped row carries a named reason. */
    public boolean skipsFullyClassified() {
        return unclassifiedSkippedCount() == 0;
    }

    /** Count for one reason; {@code 0} when no row was skipped for it. */
    public int skippedCount(SkipReason reason) {
        Integer n = reason == null ? null : skippedByReason.get(reason);
        return n == null ? 0 : n;
    }

    /**
     * Operator-facing, human-ordered reason split, e.g.
     * {@code "126 chat/session (nothing to undo), 183 superseded by a newer edit"}.
     * Empty when nothing was skipped.
     *
     * <p>Ordering is fixed (audit, untracked, superseded, entity, malformed) so
     * two operators reading two runs can compare them line by line. An
     * unclassified remainder is named, never folded into a total.</p>
     */
    public String skipReasonDetail() {
        if (skippedIds.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(SkipReason.values().length + 1);
        appendReason(parts, SkipReason.AUDIT_ONLY, "chat/session (nothing to undo)");
        appendReason(parts, SkipReason.INTERACTION_ONLY, "clicks recorded, nothing to undo");
        appendReason(parts, SkipReason.UNTRACKED_STATE, "state not tracked, cannot invert");
        appendReason(parts, SkipReason.SUPERSEDED_POSITION, "superseded by a newer edit");
        appendReason(parts, SkipReason.UNSUPPORTED_ENTITY, "entity restore unsupported");
        appendReason(parts, SkipReason.MALFORMED_PAYLOAD, "stored payload unreadable");
        appendReason(parts, SkipReason.MISSING_ITEM_IDENTITY,
            "no item NBT captured (storage.persistNbt was off)");
        appendReason(parts, SkipReason.MISSING_ACTOR, "row has no actor, cannot be aimed");
        int unclassified = unclassifiedSkippedCount();
        if (unclassified > 0) {
            parts.add(unclassified + " unclassified");
        }
        return String.join(", ", parts);
    }

    private void appendReason(List<String> parts, SkipReason reason, String label) {
        int n = skippedCount(reason);
        if (n > 0) {
            parts.add(n + " " + label);
        }
    }

    /** Applied world mutations that were marked in the DAO. */
    public int appliedCount() {
        return affectedIds.size();
    }

    /**
     * {@code true} only for a fully successful (or successful preview/no-op) request.
     * PARTIAL / FAILED / COMPENSATED / REPAIR_REQUIRED are not success.
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * World state may have changed. Undo history should retain these results.
     */
    public boolean mutatedWorld() {
        return !preview && (appliedCount() > 0 || compensatedCount > 0 || repairRequiredCount > 0);
    }

    /**
     * The inverse of {@link #mode()}: {@code ROLLBACK}→{@code RESTORE} and
     * vice versa. Useful when wiring {@code /vg undo} — pop an entry and
     * apply the inverse mode to {@link #originalFilter()}.
     */
    public Mode inverseMode() {
        return mode == Mode.ROLLBACK ? Mode.RESTORE : Mode.ROLLBACK;
    }
}
