package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2-01 regression suite: proves the new two-phase (plan → execute) pipeline
 * in {@link RollbackEngine} really separates the two phases with no side
 * effects during {@code plan(...)}.
 */
class RollbackPlanTest {

    private GuardianDao dao;
    private RecordingMutator mutator;
    private RollbackEngine engine;
    private QueryFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(GuardianDao.class);
        mutator = new RecordingMutator();
        Executor sync = Runnable::run;
        engine = new RollbackEngine(dao, mutator, sync);
        filter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 86_400_000L)
            .build();
        when(dao.closeRollbackBatch(anyLong())).thenReturn(1);
    }

    // ---------------------------------------------------------------- plan()

    @Test
    void planWithZeroMatchesIsEmptyButTaggedWithContext() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        UUID actor = UUID.randomUUID();

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, actor);

        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.plannedSteps()).isZero();
        assertThat(plan.actionIds()).isEmpty();
        assertThat(plan.skippedIds()).isEmpty();
        assertThat(plan.mode()).isEqualTo(RollbackResult.Mode.ROLLBACK);
        assertThat(plan.actorUuid()).isEqualTo(actor);
        assertThat(plan.originalFilter()).isNotNull();
        // rolledBack must be normalized to FALSE for rollback
        assertThat(plan.originalFilter().rolledBack()).isEqualTo(Boolean.FALSE);
    }

    @Test
    void planForRestoreNormalizesFilterToRolledBackTrue() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.RESTORE, null);
        assertThat(plan.originalFilter().rolledBack()).isEqualTo(Boolean.TRUE);
        assertThat(plan.mode()).isEqualTo(RollbackResult.Mode.RESTORE);
    }

    @Test
    void planPreservesWorldEditPlayerWhenNormalizingRolledBackState() throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of());
        UUID worldEditPlayer = UUID.randomUUID();
        QueryFilter weFilter = QueryFilter.builder()
            .sinceMillis(System.currentTimeMillis() - 86_400_000L)
            .worldEditPlayer(worldEditPlayer)
            .build();

        RollbackPlan rollback = engine.plan(weFilter, RollbackResult.Mode.ROLLBACK, null);
        RollbackPlan restore = engine.plan(weFilter, RollbackResult.Mode.RESTORE, null);

        assertThat(rollback.originalFilter().worldEditPlayer()).isEqualTo(worldEditPlayer);
        assertThat(restore.originalFilter().worldEditPlayer()).isEqualTo(worldEditPlayer);
    }

    @Test
    void planIsPureNoBatchOpenedNoMutatorCalledNoMarkings() throws Exception {
        Action place = action(1L, 100L, ActionType.BLOCK_PLACE,
            "w", 0, 64, 0, "minecraft:stone", null, 1, false);
        Action chat = action(2L, 101L, ActionType.CHAT,
            "w", 0, 64, 0, "hi", null, 0, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place, chat));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);

        // planned + skipped populated
        assertThat(plan.plannedSteps()).isEqualTo(1);
        assertThat(plan.actionIds()).containsExactly(1L);
        assertThat(plan.skippedIds()).containsExactly(2L);
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).action().id()).isEqualTo(1L);

        // ...but zero side effects.
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
        verify(dao, never()).closeRollbackBatch(anyLong());
    }

    @Test
    void olderBlocksAtTheSamePositionAreCountedAsSkipped() throws Exception {
        // diamond_ore broken, then cobble placed twice. Only the newest row
        // restores the block. The two older rows used to disappear with
        // skipped=0, so the result looked like one action was the whole story.
        Action broken = action(1L, 100L, ActionType.BLOCK_BREAK,
            "minecraft:overworld", 4, 64, 4, "minecraft:diamond_ore", null, 1, false);
        Action firstCobble = action(2L, 200L, ActionType.BLOCK_PLACE,
            "minecraft:overworld", 4, 64, 4, "minecraft:cobblestone", null, 1, false);
        Action secondCobble = action(3L, 300L, ActionType.BLOCK_PLACE,
            "minecraft:overworld", 4, 64, 4, "minecraft:cobblestone", null, 1, false);

        RollbackPlan built = RollbackPlan.build(List.of(broken, firstCobble, secondCobble));
        assertThat(built.actionIds()).containsExactly(3L);
        assertThat(built.skippedIds()).containsExactly(2L, 1L);

        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of(secondCobble, firstCobble, broken));
        RollbackPlan planned = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        assertThat(planned.actionIds()).containsExactly(3L);
        assertThat(planned.skippedIds()).containsExactly(2L, 1L);
    }

    @Test
    void planCanBeDiscardedWithoutSideEffects() throws Exception {
        Action place = action(3L, 100L, ActionType.BLOCK_PLACE,
            "w", 1, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        assertThat(plan.plannedSteps()).isEqualTo(1);
        // simply drop `plan` on the floor — no execute() call.

        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    // ------------------------------------------------------------- execute()

    @Test
    void executeAppliesTheAlreadyBuiltPlan() throws Exception {
        Action place = action(4L, 100L, ActionType.BLOCK_PLACE,
            "w", 2, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(42L);
        when(dao.closeRollbackBatch(42L)).thenReturn(1);

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        RollbackResult r = engine.execute(plan, false);

        assertThat(r.dispatchedSteps()).isEqualTo(1);
        assertThat(r.affectedIds()).containsExactly(4L);
        assertThat(r.originalFilter()).isSameAs(plan.originalFilter());
        assertThat(mutator.calls).containsExactly(
            "clearBlock|w|2|64|0"
        );
        verify(dao).markRolledBack(List.of(4L), true);
        verify(dao).closeRollbackBatch(42L);
    }

    @Test
    void executePreviewLeavesEverythingAlone() throws Exception {
        Action place = action(5L, 100L, ActionType.BLOCK_PLACE,
            "w", 3, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);
        RollbackResult r = engine.execute(plan, true);

        assertThat(r.preview()).isTrue();
        assertThat(r.plannedSteps()).isEqualTo(1);
        assertThat(r.dispatchedSteps()).isZero();
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao, never()).markRolledBack(any(), anyBoolean());
    }

    @Test
    void executeCanBeCalledTwiceOnSamePlanIndependently() throws Exception {
        Action a1 = action(6L, 100L, ActionType.BLOCK_PLACE,
            "w", 10, 64, 0, "minecraft:stone", null, 1, false);
        Action a2 = action(7L, 101L, ActionType.BLOCK_PLACE,
            "w", 11, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(a1, a2));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(1L, 2L);

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);

        // preview first, then real execute — no state carried between calls
        RollbackResult preview = engine.execute(plan, true);
        RollbackResult real = engine.execute(plan, false);

        assertThat(preview.dispatchedSteps()).isZero();
        assertThat(real.dispatchedSteps()).isEqualTo(2);
        assertThat(mutator.calls).hasSize(2);
    }

    @Test
    void planDoesNotCoordDedupeExplosionChunksOrContainerDeltas() {
        Action explosionChunkA = action(21L, 200L, ActionType.EXPLOSION,
            "w", 0, 64, 0, "0:64:0=minecraft:stone", null, 1, false);
        Action explosionChunkB = action(22L, 199L, ActionType.EXPLOSION,
            "w", 0, 64, 0, "1:64:0=minecraft:dirt", null, 1, false);
        Action deposit = action(23L, 198L, ActionType.CONTAINER_DEPOSIT,
            "w", 0, 64, 0, "minecraft:diamond", null, 1, false);
        Action withdraw = action(24L, 197L, ActionType.CONTAINER_WITHDRAW,
            "w", 0, 64, 0, "minecraft:emerald", null, 1, false);

        RollbackPlan plan = RollbackPlan.build(List.of(withdraw, deposit, explosionChunkB, explosionChunkA),
            filter, RollbackResult.Mode.ROLLBACK, null);

        assertThat(plan.actionIds()).containsExactly(21L, 22L, 23L, 24L);
    }

    @Test
    void executeRejectsPlanBuiltWithoutMode() {
        RollbackPlan legacy = RollbackPlan.build(List.of()); // no mode
        assertThatThrownBy(() -> engine.execute(legacy, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no mode");
    }

    @Test
    void executeRejectsNull() {
        assertThatThrownBy(() -> engine.execute(null, false))
            .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------- result plumbing

    @Test
    void resultCarriesOriginalFilterForUndoReplay() throws Exception {
        Action place = action(8L, 100L, ActionType.BLOCK_PLACE,
            "w", 4, 64, 0, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        RollbackResult r = engine.rollback(filter, false);

        // A2: originalFilter must be present on the result so /vg undo can
        // pop it and call restore(filter) later.
        assertThat(r.originalFilter()).isNotNull();
        assertThat(r.originalFilter().rolledBack()).isEqualTo(Boolean.FALSE);
        assertThat(r.inverseMode()).isEqualTo(RollbackResult.Mode.RESTORE);
    }

    @Test
    void restoreOrdersInventoryReplacementOldestFirstWithoutChangingRollbackOrder() {
        // Realistic timestamps for a fresh rollback. Note that build() itself
        // never applies originalFilter — that happens in the DAO query — so the
        // window matters only for the engine paths, but the relative order here
        // still has to be sane for the interleaved case below.
        long base = System.currentTimeMillis() - 3_600_000L;
        // Plan-time validation requires item identity for inventory rows: a
        // deposit/withdraw without item NBT is refused before it can move the
        // wrong stack. Supply NBT so these rows are plannable.
        byte[] nbt = new byte[] {1, 2, 3};
        Action oldWithdraw = actionWithItemNbt(10L, base, ActionType.INVENTORY_WITHDRAW,
                "minecraft:overworld", 0, 64, 0, "minecraft:diamond", nbt);
        Action newDeposit = actionWithItemNbt(11L, base, ActionType.INVENTORY_DEPOSIT,
                "minecraft:overworld", 0, 64, 0, "minecraft:diamond", nbt);

        RollbackPlan restore = RollbackPlan.build(List.of(newDeposit, oldWithdraw), filter,
                RollbackResult.Mode.RESTORE, UUID.randomUUID());
        assertThat(restore.ordered()).extracting(Action::type)
                .containsExactly(ActionType.INVENTORY_WITHDRAW, ActionType.INVENTORY_DEPOSIT);

        Action world = action(20L, base + 10_000L, ActionType.BLOCK_PLACE,
                "minecraft:overworld", 9, 64, 9, "minecraft:stone", null, 1, false);
        RollbackPlan interleaved = RollbackPlan.build(List.of(newDeposit, world, oldWithdraw), filter,
                RollbackResult.Mode.RESTORE, UUID.randomUUID());
        // The world row is the newest, so it keeps dispatch slot 0 while the two
        // inventory slots behind it are rewritten oldest-first. An epoch-110
        // timestamp here sorted the block row last and inverted this assertion.
        assertThat(interleaved.ordered()).extracting(Action::type)
                .containsExactly(ActionType.BLOCK_PLACE, ActionType.INVENTORY_WITHDRAW,
                        ActionType.INVENTORY_DEPOSIT);

        RollbackPlan rollback = RollbackPlan.build(List.of(oldWithdraw, newDeposit), filter,
                RollbackResult.Mode.ROLLBACK, UUID.randomUUID());
        assertThat(rollback.ordered()).extracting(Action::type)
                .containsExactly(ActionType.INVENTORY_DEPOSIT, ActionType.INVENTORY_WITHDRAW);
    }


    private static Action actionWithItemNbt(long id, long ts, ActionType type,
                                            String world, int x, int y, int z,
                                            String targetId, byte[] itemNbt) {
        // The canonical record has 24 components; after sourceTag come
        // signSide, signDyeColor, signWaxed, oldBlockState, newBlockState,
        // blockEntityNbt, itemNbt, entityNbt, pairId, inventorySlot. Item
        // identity lives in the seventh of those. Dropping a placeholder moves
        // the payload into blockEntityNbt and leaves itemNbt null, which
        // plan-time validation correctly refuses — the fixture, not the engine,
        // is what has to be right here.
        return new Action(
            id, ts, type,
            UUID.randomUUID(), "Player",
            world, x, y, z,
            targetId, null, 1, false, null,
            /*signSide*/ null, /*signDyeColor*/ null, /*signWaxed*/ null,
            /*oldBlockState*/ null, /*newBlockState*/ null,
            /*blockEntityNbt*/ null, /*itemNbt*/ itemNbt,
            /*entityNbt*/ null, /*pairId*/ null, /*inventorySlot*/ null);
    }

    private static Action action(long id, long ts, ActionType type,
                                 String world, int x, int y, int z,
                                 String targetId, String meta, int amount,
                                 boolean rolledBack) {
        return new Action(
            id, ts, type,
            UUID.randomUUID(), "Player",
            world, x, y, z,
            targetId, meta, amount, rolledBack, null
        );
    }

    /**
     * Item-flow row with the v1.3.1 NBT fidelity columns populated, which is how
     * the producers write ITEM_DROP / ITEM_PICKUP when {@code storage.persistNbt}
     * is on. {@code itemNbt == null} models a row written while it was off.
     */
    private static Action itemAction(long id, long ts, ActionType type,
                                     String world, int x, int y, int z,
                                     String itemId, int amount, byte[] itemNbt,
                                     boolean rolledBack) {
        return new Action(
            id, ts, type,
            UUID.randomUUID(), "Player",
            world, x, y, z,
            itemId, null, amount, rolledBack, null,
            null, null, null,
            null, null, null, itemNbt, null
        );
    }

    // =====================================================================
    // LANE 1 — item drop / pickup are reversible
    // =====================================================================

    @Test
    void itemDropIsAdmittedToThePlanAndRolledBackByRemovingTheItemEntity() throws Exception {
        Action drop = itemAction(11L, 100L, ActionType.ITEM_DROP,
            "minecraft:overworld", 4, 65, 7, "minecraft:netherite_sword", 1, null, false);

        RollbackPlan plan = RollbackPlan.build(List.of(drop), filter,
            RollbackResult.Mode.ROLLBACK, null);
        assertThat(plan.plannedSteps()).isEqualTo(1);
        assertThat(plan.skipped()).isEmpty();

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(drop));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(5L);

        RollbackResult r = engine.execute(plan, false);

        assertThat(r.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(r.affectedIds()).containsExactly(11L);
        assertThat(mutator.calls).containsExactly(
            "removeEntity|minecraft:overworld|4|65|7|minecraft:item");
    }

    @Test
    void itemPickupRollbackGivesTheStackBackWithItsOriginalNbt() throws Exception {
        byte[] itemNbt = "NAMED_SWORD".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Action pickup = itemAction(12L, 200L, ActionType.ITEM_PICKUP,
            "w", 1, 64, 2, "minecraft:netherite_sword", 1, itemNbt, false);

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(pickup));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(6L);

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(r.affectedIds()).containsExactly(12L);
        assertThat(mutator.calls).containsExactly(
            "giveOrDropNbt|w|1|64|2|minecraft:netherite_sword|1|11");
    }

    @Test
    void restoreOfItemDropReplacesTheStackAndRestoreOfPickupTakesItBack() throws Exception {
        Action drop = itemAction(13L, 300L, ActionType.ITEM_DROP,
            "w", 2, 64, 2, "minecraft:diamond", 3, null, true);
        Action pickup = itemAction(14L, 301L, ActionType.ITEM_PICKUP,
            "w", 2, 64, 2, "minecraft:emerald", 2, null, true);

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(drop, pickup));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(7L);

        RollbackResult r = engine.restore(filter, false);

        assertThat(r.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(mutator.calls).containsExactlyInAnyOrder(
            "giveOrDrop|w|2|64|2|minecraft:diamond|3|null",
            "removePlayer|" + pickup.actorUuid() + "|minecraft:emerald|2");
    }

    @Test
    void itemDropAndPickupAreRollbackableAtTheTypeLevel() {
        assertThat(RollbackEngine.isRollbackable(ActionType.ITEM_DROP)).isTrue();
        assertThat(RollbackEngine.isRollbackable(ActionType.ITEM_PICKUP)).isTrue();
    }

    // =====================================================================
    // LANE 1 — skipped is not one number
    // =====================================================================

    @Test
    void skippedRowsAreSplitByReasonAndTotalsReconcile() throws Exception {
        Action chat = action(1L, 500L, ActionType.CHAT, "w", 0, 0, 0, "hello", null, 0, false);
        Action sign = action(2L, 499L, ActionType.SIGN, "w", 0, 0, 0, "text", null, 0, false);
        Action dispense = action(3L, 498L, ActionType.DISPENSE, "w", 5, 64, 5, "minecraft:arrow", null, 1, false);
        Action piston = action(4L, 497L, ActionType.PISTON_EXTEND, "w", 6, 64, 5, "minecraft:stone", null, 1, false);
        Action newest = action(5L, 496L, ActionType.BLOCK_PLACE, "w", 7, 64, 5, "minecraft:stone", null, 1, false);
        Action older = action(6L, 495L, ActionType.BLOCK_BREAK, "w", 7, 64, 5, "minecraft:dirt", null, 1, false);
        Action badBlast = action(7L, 494L, ActionType.EXPLOSION, "w", 8, 64, 5, "not-a-coordinate-list", null, 1, false);

        // One reversible row, so the run has both an applied and a skipped side.
        Action remover = action(8L, 493L, ActionType.BLOCK_PLACE, "w", 9, 64, 5, "minecraft:stone", null, 1, false);

        when(dao.query(any(), anyInt(), anyInt()))
            .thenReturn(List.of(chat, sign, dispense, piston, newest, older, badBlast, remover));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(31L);

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.skippedCount()).isEqualTo(6);
        assertThat(r.skippedCount(RollbackResult.SkipReason.AUDIT_ONLY)).isEqualTo(2);
        assertThat(r.skippedCount(RollbackResult.SkipReason.UNTRACKED_STATE)).isEqualTo(2);
        assertThat(r.skippedCount(RollbackResult.SkipReason.SUPERSEDED_POSITION)).isEqualTo(1);
        assertThat(r.skippedCount(RollbackResult.SkipReason.MALFORMED_PAYLOAD)).isEqualTo(1);
        assertThat(r.skipsFullyClassified()).isTrue();
        assertThat(r.unclassifiedSkippedCount()).isZero();

        // The operator-facing split names every bucket, in a stable order.
        String detail = r.skipReasonDetail();
        assertThat(detail)
            .contains("2 chat/session (nothing to undo)")
            .contains("2 state not tracked, cannot invert")
            .contains("1 superseded by a newer edit")
            .contains("1 stored payload unreadable");
        assertThat(r.skippedCount()).isEqualTo(6);
    }

    @Test
    void aFullySkippedRequestStillRecordsAnAuditBatchAndClosesIt() throws Exception {
        Action chat = action(1L, 500L, ActionType.CHAT, "w", 0, 0, 0, "hello", null, 0, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(chat));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(32L);

        RollbackResult r = engine.rollback(filter, false);

        // The operator asked for something and matched a row. The audit row must
        // exist, and it must not be left OPEN for crash recovery to find.
        assertThat(mutator.calls).isEmpty();
        assertThat(r.plannedSteps()).isZero();
        assertThat(r.skippedCount(RollbackResult.SkipReason.AUDIT_ONLY)).isEqualTo(1);
        assertThat(r.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(r.batchClosed()).isTrue();
        verify(dao).openRollbackBatch(any(), anyInt(), anyString(), any());
        verify(dao).closeRollbackBatch(32L);
    }

    @Test
    void scannedRowsAlwaysEqualPlannedPlusSkipped() throws Exception {
        List<Action> rows = new ArrayList<>();
        long id = 100;
        for (int i = 0; i < 30; i++) {
            rows.add(action(id + i, 1_000L + i, ActionType.BLOCK_PLACE,
                "w", i, 64, 0, "minecraft:stone", null, 1, false));
        }
        for (int i = 0; i < 12; i++) {
            rows.add(action(id + 100 + i, 2_000L + i, ActionType.CHAT,
                "w", 0, 0, 0, "msg" + i, null, 0, false));
        }
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(rows);
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(8L);

        RollbackResult r = engine.rollback(filter, false);

        assertThat(rows.size())
            .as("no row may vanish between the DAO result and the result counts")
            .isEqualTo(r.plannedSteps() + r.skippedCount());
        assertThat(r.skippedCount(RollbackResult.SkipReason.AUDIT_ONLY)).isEqualTo(12);
    }

    @Test
    void perReasonCountsSurviveAMixedAppliedAndSkippedRun() throws Exception {
        Action place = action(21L, 900L, ActionType.BLOCK_PLACE,
            "w", 3, 64, 3, "minecraft:stone", null, 1, false);
        Action chat = action(22L, 899L, ActionType.CHAT, "w", 0, 0, 0, "hi", null, 0, false);
        Action piston = action(23L, 898L, ActionType.PISTON_RETRACT,
            "w", 4, 64, 3, "minecraft:piston_head", null, 1, false);

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place, chat, piston));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(9L);

        RollbackResult r = engine.rollback(filter, false);

        assertThat(r.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(r.affectedIds()).containsExactly(21L);
        assertThat(r.skippedIds()).containsExactlyInAnyOrder(22L, 23L);
        assertThat(r.skippedCount(RollbackResult.SkipReason.AUDIT_ONLY)).isEqualTo(1);
        assertThat(r.skippedCount(RollbackResult.SkipReason.UNTRACKED_STATE)).isEqualTo(1);
    }

    // =====================================================================
    // LANE 1 — plan validates before mutate
    // =====================================================================

    @Test
    void unparsableExplosionPayloadIsRejectedAtPlanTimeNotMidRun() throws Exception {
        Action badBlast = action(31L, 700L, ActionType.EXPLOSION,
            "w", 0, 64, 0, "garbage-without-equals", null, 1, false);

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(badBlast));

        RollbackPlan plan = engine.plan(filter, RollbackResult.Mode.ROLLBACK, null);

        // Known before the world is touched: nothing is planned, nothing runs.
        assertThat(plan.plannedSteps()).isZero();
        assertThat(plan.invalid()).extracting(Action::id).containsExactly(31L);
        assertThat(plan.skippedCount(RollbackResult.SkipReason.MALFORMED_PAYLOAD)).isEqualTo(1);

        // Executing that plan cannot discover the defect because it never runs.
        RollbackResult r = engine.execute(plan, false);
        assertThat(mutator.calls).isEmpty();
        assertThat(r.status()).isEqualTo(RollbackResult.Status.SUCCESS);
        assertThat(r.skippedCount(RollbackResult.SkipReason.MALFORMED_PAYLOAD)).isEqualTo(1);
        assertThat(r.plannedSteps()).isZero();
    }

    @Test
    void inventoryRowWithoutItemPayloadIsRejectedAtPlanTime() throws Exception {
        // Built through ActionBuilder so itemNbt is genuinely null. The 14-arg
        // constructor cannot reach the itemNbt component, which is why this test
        // silently asserted nothing after the skip reason was split out.
        Action noNbt = new network.vonix.guardian.core.action.ActionBuilder()
            .id(41L)
            .timestamp(600L)
            .type(ActionType.INVENTORY_WITHDRAW)
            .actorUuid(UUID.randomUUID())
            .actorName("Player")
            .worldId("w")
            .position(0, 64, 0)
            .targetId("minecraft:diamond")
            .amount(1)
            .build();

        RollbackPlan plan = RollbackPlan.build(List.of(noNbt), filter,
            RollbackResult.Mode.ROLLBACK, null);

        assertThat(plan.plannedSteps()).isZero();
        // Distinct from MALFORMED_PAYLOAD: the payload was never captured because
        // storage.persistNbt was off at write time. Reporting it as corruption sent
        // an operator hunting for damage when the fix was one config key.
        assertThat(plan.skippedCount(RollbackResult.SkipReason.MISSING_ITEM_IDENTITY))
            .as("actual skip reasons: %s", plan.skippedEntries().stream()
                .map(x -> String.valueOf(x.reason())).toList())
            .isEqualTo(1);
        assertThat(plan.skippedEntries()).singleElement()
            .satisfies(s -> assertThat(s.reason())
                .isEqualTo(RollbackResult.SkipReason.MISSING_ITEM_IDENTITY));

        // A payload parked in blockEntityNbt is NOT item identity. This is the
        // exact shape a misaligned Action constructor call produces, and moving
        // the wrong stack is the failure this validation exists to prevent, so
        // a neighbouring NBT column must never satisfy it.
        Action nbtInWrongSlot = new Action(
            43L, 600L, ActionType.INVENTORY_DEPOSIT,
            UUID.randomUUID(), "Player",
            "w", 0, 64, 0, "minecraft:diamond", null, 1, false, null,
            null, null, null,
            null, null,
            new byte[] {1, 2, 3}, /* blockEntityNbt, not itemNbt */
            null, null, null, null);

        RollbackPlan wrongSlot = RollbackPlan.build(List.of(nbtInWrongSlot), filter,
            RollbackResult.Mode.ROLLBACK, null);

        assertThat(wrongSlot.plannedSteps()).isZero();
        // The row's itemNbt is null - the payload sits in blockEntityNbt instead - so
        // the reason is the missing-identity one. The property this test protects is
        // that a neighbouring NBT column never satisfies item identity, and that holds
        // whichever of the two names the rejection carries.
        assertThat(wrongSlot.skippedCount(RollbackResult.SkipReason.MISSING_ITEM_IDENTITY)).isEqualTo(1);
        // The property under test is that this row was rejected, not which of the two
        // payload reasons it carries. `invalid()` only collects MALFORMED_PAYLOAD, so
        // assert against the skip list, which is the authoritative record.
        assertThat(wrongSlot.skippedEntries()).extracting(x -> x.action().id()).containsExactly(43L);
    }

    @Test
    void inventoryRowWithoutActorIsRejectedAtPlanTime() throws Exception {
        Action noActor = new Action(
            42L, 600L, ActionType.INVENTORY_DEPOSIT,
            null, "Player",
            "w", 0, 64, 0, "minecraft:diamond", null, 1, false, null,
            null, null, null, null, null, null, new byte[]{1}, null, null, null);

        RollbackPlan plan = RollbackPlan.build(List.of(noActor), filter,
            RollbackResult.Mode.ROLLBACK, null);

        assertThat(plan.plannedSteps()).isZero();
        assertThat(plan.skippedCount(RollbackResult.SkipReason.MISSING_ACTOR)).isEqualTo(1);
    }

    @Test
    void restoreOfAnEntityKillIsSkippedAtPlanTimeNotAtDispatch() throws Exception {
        Action kill = action(51L, 500L, ActionType.ENTITY_KILL,
            "w", 1, 64, 1, "minecraft:zombie", null, 1, true);

        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(kill));

        RollbackResult r = engine.restore(filter, false);

        assertThat(mutator.calls).isEmpty();
        assertThat(r.skippedCount(RollbackResult.SkipReason.UNSUPPORTED_ENTITY)).isEqualTo(1);
        // Plan-time resolution means the count was never a dispatch surprise.
        assertThat(r.skippedIds()).containsExactly(51L);
    }

    // =====================================================================
    // LANE 1 — concurrent rollbacks are mutually excluded
    // =====================================================================

    @Test
    void secondRollbackWaitsForTheActiveOneInsteadOfPlanningTheSamePreState() throws Exception {
        Action place = action(61L, 400L, ActionType.BLOCK_PLACE,
            "w", 9, 64, 9, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(11L, 12L);

        java.util.concurrent.CountDownLatch firstInside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean firstApplied = new java.util.concurrent.atomic.AtomicBoolean();

        Executor gatedMainThread = task -> new Thread(() -> {
            firstInside.countDown();
            try {
                releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
            firstApplied.set(true);
        }, "gated-main").start();

        RollbackEngine serial = new RollbackEngine(dao, mutator, gatedMainThread, Runnable::run);
        java.util.concurrent.CompletableFuture<RollbackResult> first =
            serial.rollbackAsync(filter, false).toCompletableFuture();
        assertThat(firstInside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        java.util.concurrent.atomic.AtomicBoolean secondReachedPlanning =
            new java.util.concurrent.atomic.AtomicBoolean();
        Thread second = new Thread(() -> {
            try {
                serial.rollback(filter, false);
                secondReachedPlanning.set(true);
            } catch (Exception ignored) {
                // Asserted below through the wait counter.
            }
        }, "second-rollback");
        second.start();

        // The second rollback must block on the exclusion permit, not plan and
        // apply against the same pre-state while the first is still running.
        assertThat(awaitTrue(() -> serial.exclusionWaitCount() == 1, 5_000L))
            .as("second rollback must record a wait on the exclusion permit")
            .isTrue();
        assertThat(serial.rollbackInProgress()).isTrue();
        assertThat(secondReachedPlanning).isFalse();
        assertThat(firstApplied).isFalse();

        releaseFirst.countDown();
        second.join(5_000L);
        first.join();

        assertThat(secondReachedPlanning).isTrue();
        assertThat(serial.rollbackInProgress()).isFalse();
        assertThat(serial.exclusionWaitCount()).isEqualTo(1);
    }

    /** Poll a condition with a deadline; avoids sleeps that make a race look deterministic. */
    private static boolean awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void previewDoesNotBlockOnARunningRollback() throws Exception {
        Action place = action(71L, 400L, ActionType.BLOCK_PLACE,
            "w", 10, 64, 10, "minecraft:stone", null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(place));

        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Executor gated = task -> new Thread(() -> {
            inside.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
        }, "gated").start();

        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(13L);
        RollbackEngine serial = new RollbackEngine(dao, mutator, gated, Runnable::run);
        serial.rollbackAsync(filter, false);
        assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // A preview must still answer while the real rollback is in flight.
        RollbackResult preview = serial.rollback(filter, true);
        assertThat(preview.preview()).isTrue();
        assertThat(serial.exclusionWaitCount()).isZero();

        release.countDown();
    }

    // =====================================================================
    // LANE 1 — a partially applied explosion is REPAIR_REQUIRED
    // =====================================================================

    @Test
    void partlyAppliedExplosionIsRepairRequiredNotFailed() throws Exception {
        String affected = "0:64:0=minecraft:stone,1:64:0=minecraft:dirt,2:64:0=minecraft:sand";
        Action blast = action(81L, 900L, ActionType.EXPLOSION, "w", 0, 64, 0, affected, null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(blast));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(21L);
        when(dao.markRepairRequired(any())).thenReturn(1);

        // First block restores, second throws. One block DID change.
        RecordingMutator partial = new RecordingMutator() {
            private int seq;
            @Override
            public boolean trySetBlock(String worldId, int x, int y, int z,
                                       String targetId, String targetMeta) {
                if (seq++ == 1) {
                    throw new IllegalStateException("chunk unloaded mid-restore");
                }
                return super.trySetBlock(worldId, x, y, z, targetId, targetMeta);
            }
        };
        RollbackEngine repairing = new RollbackEngine(dao, partial, Runnable::run);

        RollbackResult r = repairing.rollback(filter, false);

        assertThat(r.status()).isEqualTo(RollbackResult.Status.REPAIR_REQUIRED);
        assertThat(r.repairRequiredCount()).isEqualTo(1);
        assertThat(r.failedCount()).isZero();
        assertThat(r.batchClosed()).isFalse();
        verify(dao).markRepairRequired(argThat(rows -> rows.size() == 1
            && rows.get(0).actionId() == 81L
            && rows.get(0).reason().contains("1 of 3")));
    }

    @Test
    void explosionThatAppliesNothingStaysFailedAndWritesNoRepairRow() throws Exception {
        String affected = "0:64:0=minecraft:stone,1:64:0=minecraft:dirt";
        Action blast = action(82L, 900L, ActionType.EXPLOSION, "w", 0, 64, 0, affected, null, 1, false);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(blast));
        when(dao.openRollbackBatch(any(), anyInt(), anyString(), any())).thenReturn(22L);

        WorldMutator refuses = new RecordingMutator() {
            @Override
            public boolean trySetBlock(String worldId, int x, int y, int z,
                                       String targetId, String targetMeta) {
                return false;
            }
        };
        RollbackEngine failing = new RollbackEngine(dao, refuses, Runnable::run);

        RollbackResult r = failing.rollback(filter, false);

        // Nothing changed, so this is an honest FAILED — no repair row needed.
        assertThat(r.status()).isEqualTo(RollbackResult.Status.FAILED);
        assertThat(r.repairRequiredCount()).isZero();
        verify(dao, never()).markRepairRequired(any());
    }

    private static class RecordingMutator implements WorldMutator {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {
            calls.add("setBlock|" + worldId + "|" + x + "|" + y + "|" + z + "|" + targetId + "|" + targetMeta);
            return true;
        }

        @Override
        public boolean trySetBlock(String worldId, int x, int y, int z, String targetId, String targetMeta,
                                   String blockState, byte[] blockEntityNbt) {
            calls.add("setBlockNbt|" + worldId + "|" + x + "|" + y + "|" + z + "|" + targetId
                + "|" + targetMeta + "|" + blockState);
            return true;
        }

        @Override
        public boolean tryClearBlock(String worldId, int x, int y, int z) {
            calls.add("clearBlock|" + worldId + "|" + x + "|" + y + "|" + z);
            return true;
        }

        @Override
        public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
            calls.add("giveOrDrop|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount + "|" + targetMeta);
            return true;
        }

        @Override
        public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount,
                                     String targetMeta, byte[] itemNbt) {
            calls.add("giveOrDropNbt|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId
                + "|" + amount + "|" + (itemNbt == null ? 0 : itemNbt.length));
            return true;
        }

        @Override
        public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
            calls.add("removeFromContainer|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount);
            return true;
        }

        @Override
        public boolean tryRemoveFromPlayerInventory(UUID playerUuid, String itemId, int amount,
                                                    String targetMeta, byte[] itemNbt, Integer inventorySlot) {
            calls.add("removePlayer|" + playerUuid + "|" + itemId + "|" + amount);
            return true;
        }

        @Override
        public boolean tryRespawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {
            calls.add("respawnEntity|" + worldId + "|" + x + "|" + y + "|" + z + "|" + entityType + "|" + targetMeta);
            return true;
        }

        @Override
        public boolean tryRemoveEntity(String worldId, int x, int y, int z, String entityType) {
            calls.add("removeEntity|" + worldId + "|" + x + "|" + y + "|" + z + "|" + entityType);
            return true;
        }
    }
}
