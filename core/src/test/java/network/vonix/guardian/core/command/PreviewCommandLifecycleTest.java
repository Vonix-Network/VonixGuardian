package network.vonix.guardian.core.command;

import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.RollbackPreviewStore;
import network.vonix.guardian.core.rollback.RollbackResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The apply/cancel lifecycle of a pending rollback preview, asserted against
 * {@link RollbackPreviewStore} — the type every cell's {@code Preview} command
 * drives.
 *
 * <h2>What this replaces</h2>
 * The previous version sliced each cell's {@code Preview} class out of the file
 * by searching for a class header and a private method, then asserted the exact
 * call spelling inside that slice. It broke on any reformat that moved the
 * method, and it could not detect a genuinely wrong lifecycle if the text
 * matched. The lifecycle that matters is a property of the store, so it is
 * asserted by driving the store.
 *
 * <p>Three defects are pinned here, each stated as an observable consequence:</p>
 * <ol>
 *   <li><b>A stale preview cannot be applied.</b> An operator runs
 *       {@code #preview}, waits, runs it again, then {@code /vg apply}. The
 *       second request must supersede the first: applying the first plan would
 *       change the world according to a plan the operator was never shown.</li>
 *   <li><b>Apply consumes the preview it saw, and only that one.</b> A worker
 *       that lost the race must not consume a newer preview it did not read.</li>
 *   <li><b>Cancel fences a late worker.</b> After cancel, an in-flight plan that
 *       finishes later must not be able to install itself as pending.</li>
 * </ol>
 */
class PreviewCommandLifecycleTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Test
    void aSecondPreviewRequestSupersedesTheFirst() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);

        long firstGeneration = store.invalidate(ACTOR);
        assertThat(store.putIfGeneration(ACTOR, firstGeneration, preview(1L))).isTrue();
        long staleGeneration = store.currentGeneration(ACTOR);

        // The operator runs #preview again.
        long secondGeneration = store.invalidate(ACTOR);
        assertThat(secondGeneration).isGreaterThan(firstGeneration);

        // The first plan's worker finishes late and tries to publish.
        assertThat(store.putIfGeneration(ACTOR, staleGeneration, preview(1L)))
                .as("a superseded preview must not become pending again")
                .isFalse();
        assertThat(store.peek(ACTOR))
                .as("the superseded preview must not be the one an apply would find")
                .isEmpty();

        assertThat(store.putIfGeneration(ACTOR, secondGeneration, preview(2L))).isTrue();
        assertThat(store.peek(ACTOR)).contains(preview(2L));
    }

    @Test
    void applyConsumesTheObservedPreviewOnlyOnce() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);
        RollbackResult shown = preview(7L);
        assertThat(store.put(shown)).isTrue();

        // The apply worker reads the exact plan and its generation together.
        Optional<RollbackPreviewStore.Snapshot> snapshot = store.snapshot(ACTOR);
        assertThat(snapshot).isPresent();
        RollbackResult expected = snapshot.get().result();

        assertThat(store.takeIfSame(ACTOR, snapshot.get().generation(), expected)).contains(expected);
        assertThat(store.takeIfSame(ACTOR, snapshot.get().generation(), expected))
                .as("a second apply of the same preview must find nothing to apply")
                .isEmpty();
        assertThat(store.peek(ACTOR)).isEmpty();
    }

    @Test
    void aWorkerThatLostTheRaceCannotConsumeTheNewerPreview() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);

        assertThat(store.put(preview(1L))).isTrue();
        RollbackResult whatTheSlowWorkerSaw = preview(1L);
        long generationTheSlowWorkerSaw = store.currentGeneration(ACTOR);

        // A newer request lands while the slow worker is still working.
        store.invalidate(ACTOR);
        assertThat(store.putIfGeneration(ACTOR, store.currentGeneration(ACTOR), preview(2L))).isTrue();

        assertThat(store.takeIfSame(ACTOR, generationTheSlowWorkerSaw, whatTheSlowWorkerSaw))
                .as("the slow worker must not consume a preview it never read")
                .isEmpty();
        assertThat(store.peek(ACTOR))
                .as("the newer preview must survive the slow worker's apply")
                .contains(preview(2L));
    }

    @Test
    void cancelFencesALateWorker() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);
        long generation = store.invalidate(ACTOR);

        assertThat(store.cancel(ACTOR))
                .as("cancel reports whether anything was pending")
                .isFalse();
        assertThat(store.putIfGeneration(ACTOR, generation, preview(1L)))
                .as("a plan that finishes after cancel must not become pending")
                .isFalse();
        assertThat(store.peek(ACTOR)).isEmpty();
    }

    @Test
    void cancelConsumesAnExistingPreviewAndAdvancesTheGeneration() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);
        assertThat(store.put(preview(3L))).isTrue();
        long before = store.currentGeneration(ACTOR);

        assertThat(store.cancel(ACTOR)).isTrue();
        assertThat(store.currentGeneration(ACTOR)).isGreaterThan(before);
        assertThat(store.peek(ACTOR)).isEmpty();
    }

    @Test
    void aNonPreviewResultNeverBecomesAPendingPreview() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);
        RollbackResult alreadyApplied = new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, false,
                List.of(1L), List.of(), 1, 1, QueryFilter.builder().build());

        assertThat(store.put(alreadyApplied)).isFalse();
        assertThat(store.peek(ACTOR)).isEmpty();
    }

    @Test
    void aPreviewWithNoAffectedRowsIsNotApplicable() {
        RollbackPreviewStore store = new RollbackPreviewStore(8);
        RollbackResult nothingToDo = new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, true,
                List.of(), List.of(), 0, 0, QueryFilter.builder().build());

        assertThat(store.put(nothingToDo)).isFalse();
        assertThat(store.peek(ACTOR))
                .as("an empty preview must not sit in the store waiting to be applied")
                .isEmpty();
    }

    private static RollbackResult preview(long affectedId) {
        return new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, true,
                List.of(affectedId), List.of(), 1, 0, QueryFilter.builder().build());
    }
}
