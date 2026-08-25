package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.query.QueryFilter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackPreviewStoreTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static RollbackResult preview(long id) {
        return new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, true,
                List.of(id), List.of(), 1, 0, QueryFilter.builder().build());
    }

    @Test
    void storesOnlyApplicablePreviewAndConsumesItOnTake() {
        RollbackPreviewStore store = new RollbackPreviewStore(2);

        assertThat(store.put(preview(7))).isTrue();
        Optional<RollbackResult> pending = store.take(ACTOR);

        assertThat(pending).contains(preview(7));
        assertThat(store.take(ACTOR)).isEmpty();
    }

    @Test
    void rejectsNonPreviewEmptyOrLegacyResultsAndClearsStaleActorPreview() {
        RollbackPreviewStore store = new RollbackPreviewStore(2);
        RollbackResult nonPreview = new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, false,
                List.of(1L), List.of(), 1, 1, QueryFilter.builder().build());
        RollbackResult empty = new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, true,
                List.of(), List.of(), 0, 0, QueryFilter.builder().build());
        RollbackResult legacy = new RollbackResult(
                ACTOR, RollbackResult.Mode.ROLLBACK, true,
                List.of(1L), List.of(), 1, 0);

        assertThat(store.put(preview(9))).isTrue();
        assertThat(store.put(nonPreview)).isFalse();
        assertThat(store.peek(ACTOR)).isEmpty();
        assertThat(store.put(preview(9))).isTrue();
        assertThat(store.put(empty)).isFalse();
        assertThat(store.peek(ACTOR)).isEmpty();
        assertThat(store.put(preview(9))).isTrue();
        assertThat(store.put(legacy)).isFalse();
        assertThat(store.size()).isZero();
    }

    @Test
    void takeIfSameOnlyConsumesTheObservedPreview() {
        RollbackPreviewStore store = new RollbackPreviewStore(2);
        RollbackResult observed = preview(11);
        assertThat(store.put(observed)).isTrue();
        assertThat(store.takeIfSame(ACTOR, preview(12))).isEmpty();
        assertThat(store.peek(ACTOR)).contains(observed);
        assertThat(store.takeIfSame(ACTOR, observed)).contains(observed);
        assertThat(store.peek(ACTOR)).isEmpty();
    }

    @Test
    void invalidationFencesLateWorkerRepublish() {
        RollbackPreviewStore store = new RollbackPreviewStore(2);
        long firstGeneration = store.invalidate(ACTOR);
        assertThat(store.putIfGeneration(ACTOR, firstGeneration, preview(20))).isTrue();
        long secondGeneration = store.invalidate(ACTOR);
        assertThat(secondGeneration).isGreaterThan(firstGeneration);
        assertThat(store.putIfGeneration(ACTOR, firstGeneration, preview(21))).isFalse();
        assertThat(store.peek(ACTOR)).isEmpty();
        assertThat(store.putIfGeneration(ACTOR, secondGeneration, preview(22))).isTrue();
    }

    @Test
    void generationTokensStayBoundedAfterManyActors() {
        RollbackPreviewStore store = new RollbackPreviewStore(2);
        long first = store.invalidate(ACTOR);
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");
        store.invalidate(other);
        store.invalidate(third);
        assertThat(store.generationEntryCount()).isEqualTo(2);
        assertThat(store.putIfGeneration(ACTOR, first, preview(30))).isFalse();
    }

    @Test
    void boundsActorEntriesAndReplacesTheActorPreview() {
        RollbackPreviewStore store = new RollbackPreviewStore(1);
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertThat(store.put(preview(1))).isTrue();
        assertThat(store.put(preview(2))).isTrue();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.take(ACTOR)).contains(preview(2));

        RollbackResult otherPreview = new RollbackResult(
                other, RollbackResult.Mode.RESTORE, true,
                List.of(3L), List.of(), 1, 0, QueryFilter.builder().build());
        assertThat(store.put(otherPreview)).isTrue();
        assertThat(store.cancel(other)).isTrue();
        assertThat(store.cancel(other)).isFalse();
    }

    @Test
    void clearRemovesAllActorPreviews() {
        RollbackPreviewStore store = new RollbackPreviewStore(4);
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000003");
        assertThat(store.put(preview(8))).isTrue();
        assertThat(store.put(new RollbackResult(
                other, RollbackResult.Mode.RESTORE, true,
                List.of(9L), List.of(), 1, 0, QueryFilter.builder().build()))).isTrue();

        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.peek(ACTOR)).isEmpty();
        assertThat(store.peek(other)).isEmpty();
    }
}
