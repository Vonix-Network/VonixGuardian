package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.ContainerTransport;
import network.vonix.guardian.core.event.HopperTransportPairs;
import network.vonix.guardian.core.event.InventoryDelta;
import network.vonix.guardian.core.event.Sentinel;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.storage.GuardianDao;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Milestone 1: hopper/container transport parity for exact slot identity,
 * pair IDs, duplicate suppression, stale pairs, and failed compensation.
 */
class ContainerTransportParityTest {

    private static final String WORLD = "minecraft:overworld";
    private static final byte[] SWORD = "NAMED_SWORD_COMPONENTS".getBytes();

    @BeforeEach
    void resetDeduper() {
        ContainerTransport.resetDuplicatesForTest();
    }

    @AfterEach
    void clearDeduper() {
        ContainerTransport.resetDuplicatesForTest();
    }

    @Test
    void slotDiff_emitsExactSlotAndIdentityReplacement() {
        List<ContainerTransport.SlotStack> before = List.of(
                new ContainerTransport.SlotStack(3, "minecraft:diamond_sword", 1, new byte[]{1}, SWORD),
                new ContainerTransport.SlotStack(5, "minecraft:dirt", 4, null, null)
        );
        List<ContainerTransport.SlotStack> after = List.of(
                new ContainerTransport.SlotStack(3, "minecraft:emerald", 1, new byte[]{2}, new byte[]{9}),
                new ContainerTransport.SlotStack(5, "minecraft:dirt", 4, null, null)
        );

        List<ContainerTransport.SlotChange> changes = ContainerTransport.diff(before, after);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).slot()).isEqualTo(3);
        assertThat(changes.get(0).kind()).isEqualTo(InventoryDelta.Kind.WITHDRAW);
        assertThat(changes.get(0).itemId()).isEqualTo("minecraft:diamond_sword");
        assertThat(changes.get(1).slot()).isEqualTo(3);
        assertThat(changes.get(1).kind()).isEqualTo(InventoryDelta.Kind.DEPOSIT);
        assertThat(changes.get(1).itemId()).isEqualTo("minecraft:emerald");
        assertThat(ContainerTransport.isReplacement(changes)).isTrue();
    }

    @Test
    void hopperPair_sharesPairIdAndExactSlots() {
        long pair = HopperTransportPairs.nextPairId();
        Action pull = hopper(ActionType.HOPPER_PULL, 10, 65, 10, 3, pair);
        Action push = hopper(ActionType.HOPPER_PUSH, 10, 64, 10, 0, pair);

        assertThat(HopperTransportPairs.isMember(pull)).isTrue();
        assertThat(HopperTransportPairs.isPair(pull, push)).isTrue();
        assertThat(HopperTransportPairs.siblingOf(pull, List.of(pull, push))).isSameAs(push);
        assertThat(pull.inventorySlot()).isEqualTo(3);
        assertThat(push.inventorySlot()).isEqualTo(0);
        assertThat(pull.itemNbt()).containsExactly(SWORD);
        assertThat(pull.oldBlockState()).isEqualTo("facing=down,enabled=true");
        assertThat(push.sourceTag()).isEqualTo(Sentinel.HOPPER);
        assertThat(pull.actorName()).isEqualTo(Sentinel.HOPPER);
    }

    @Test
    void duplicateCapture_withinWindowIsSuppressed() {
        Action first = hopper(ActionType.HOPPER_PULL, 4, 64, 4, 2, 77L);
        assertThat(ContainerTransport.suppressDuplicate(first, 1_000L)).isFalse();
        assertThat(ContainerTransport.suppressDuplicate(first, 1_020L)).isTrue();
        assertThat(ContainerTransport.suppressDuplicate(first, 1_000L + ContainerTransport.DEDUPE_WINDOW_MS + 1))
                .isFalse();
    }

    @Test
    void queryFilter_plusHopperAndPlusContainerSeeStoredRows() throws Exception {
        try (SqliteDao dao = new SqliteDao("jdbc:sqlite::memory:")) {
            dao.init();
            long pair = 44L;
            dao.insertBatch(List.of(
                    hopper(ActionType.HOPPER_PULL, 1, 65, 1, 3, pair),
                    hopper(ActionType.HOPPER_PUSH, 1, 64, 1, 0, pair),
                    new ActionBuilder()
                            .timestamp(1_700_000_000_300L)
                            .type(ActionType.CONTAINER_DEPOSIT)
                            .actorName("Notch")
                            .worldId(WORLD)
                            .position(2, 64, 2)
                            .targetId("minecraft:dirt")
                            .amount(1)
                            .inventorySlot(8)
                            .build()
            ));

            QueryParser parser = new QueryParser();
            QueryFilter hopperFilter = parser.parse("a:+hopper a:-hopper", null);
            List<Action> hopperRows = dao.query(hopperFilter, 0, 10);
            assertThat(hopperRows).extracting(Action::type)
                    .containsExactlyInAnyOrder(ActionType.HOPPER_PULL, ActionType.HOPPER_PUSH);

            QueryFilter containerFilter = parser.parse("a:+container", null);
            List<Action> containerRows = dao.query(containerFilter, 0, 10);
            assertThat(containerRows).extracting(Action::type)
                    .containsExactly(ActionType.CONTAINER_DEPOSIT);
            assertThat(containerRows.get(0).inventorySlot()).isEqualTo(8);
        }
    }

    @Test
    void staleHopperPair_failsClosedWithoutMutation() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        RecordingMutator mutator = new RecordingMutator();
        RollbackEngine engine = new RollbackEngine(dao, mutator, Runnable::run);
        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();
        when(dao.closeRollbackBatch(anyLong())).thenReturn(1);
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(5L);

        Action pull = hopperWithId(9L, ActionType.HOPPER_PULL, 1, 65, 1, 3, 905L);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(pull));
        when(dao.queryPage(any(), anyInt(), anyInt()))
                .thenReturn(new GuardianDao.QueryPage(List.of(pull), false));
        when(dao.findByPairIds(any())).thenReturn(List.of(pull));

        assertThatThrownBy(() -> engine.rollbackAsync(filter, false).toCompletableFuture().join())
                .hasCauseInstanceOf(RollbackMutationException.class);
        assertThat(mutator.calls).isEmpty();
        verify(dao, never()).markRolledBack(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(dao, never()).closeRollbackBatch(anyLong());
    }

    @Test
    void hopperPairCompensationFailure_persistsRepairRequired() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();
        long pair = 906L;
        Action pull = hopperWithId(12L, ActionType.HOPPER_PULL, 1, 65, 1, 3, pair);
        Action push = hopperWithId(13L, ActionType.HOPPER_PUSH, 1, 64, 1, 0, pair);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(pull, push));
        when(dao.queryPage(any(), anyInt(), anyInt()))
                .thenReturn(new GuardianDao.QueryPage(List.of(pull, push), false));
        when(dao.findByPairIds(any())).thenReturn(List.of(pull, push));
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(6L);
        when(dao.markRepairRequired(any())).thenReturn(2);

        RecordingMutator world = new RecordingMutator() {
            @Override
            public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                                  String targetMeta, byte[] itemNbt, Integer inventorySlot) {
                super.tryRemoveFromContainer(worldId, x, y, z, itemId, amount, targetMeta, itemNbt, inventorySlot);
                return true;
            }

            @Override
            public boolean tryAddToContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                             String targetMeta, byte[] itemNbt, Integer inventorySlot) {
                super.tryAddToContainer(worldId, x, y, z, itemId, amount, targetMeta, itemNbt, inventorySlot);
                throw new UncompensatedSlotMutationException(inventorySlot == null ? -1 : inventorySlot,
                        new IllegalStateException("add failed"), new IllegalStateException("restore failed"));
            }
        };
        RollbackEngine engine = new RollbackEngine(dao, world, Runnable::run);

        assertThatThrownBy(() -> engine.rollbackAsync(filter, false).toCompletableFuture().join())
                .hasCauseInstanceOf(RollbackMutationException.class);
        verify(dao).markRepairRequired(org.mockito.ArgumentMatchers.argThat(rows ->
                rows != null && rows.size() == 2));
        verify(dao, never()).closeRollbackBatch(anyLong());
    }

    @Test
    void previewListsHopperPairWithoutMutating() throws Exception {
        GuardianDao dao = mock(GuardianDao.class);
        RecordingMutator mutator = new RecordingMutator();
        RollbackEngine engine = new RollbackEngine(dao, mutator, Runnable::run);
        long pair = 907L;
        Action pull = hopperWithId(20L, ActionType.HOPPER_PULL, 1, 65, 1, 3, pair);
        Action push = hopperWithId(21L, ActionType.HOPPER_PUSH, 1, 64, 1, 0, pair);
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(pull, push));
        when(dao.queryPage(any(), anyInt(), anyInt()))
                .thenReturn(new GuardianDao.QueryPage(List.of(pull, push), false));
        when(dao.findByPairIds(any())).thenReturn(List.of(pull, push));

        RollbackResult preview = engine.rollback(QueryFilter.builder().sinceMillis(1L).build(), true);
        assertThat(preview.preview()).isTrue();
        assertThat(preview.affectedIds()).containsExactlyInAnyOrder(20L, 21L);
        assertThat(mutator.calls).isEmpty();
    }

    @Test
    void fabric1211ProducerAndMutator_carrySlotNbtAndFailClosedCompensation() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");
        String hopper = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/mixin/HopperBlockEntityMixin.java"));
        String bridge = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricMixinBridge.java"));
        String mutator = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricWorldMutator.java"));
        String capture = Files.readString(root.resolve(
                "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/NbtCapture.java"));

        assertThat(hopper)
                .contains("vg$snapshot")
                .contains("tryMoveInItem")
                .doesNotContain("vg$firstNonEmptySlot");
        assertThat(bridge)
                .contains("inventorySlot")
                .contains("HopperTransportPairs")
                .contains("ContainerTransport")
                .contains("submitHopperPush")
                .contains("blockStateProps");
        assertThat(mutator)
                .contains("tryAddToContainer")
                .contains("tryRemoveFromContainer")
                .contains("restoreExactSlotOrThrow")
                .contains("UncompensatedSlotMutationException");
        assertThat(capture).contains("MAX_NBT_BYTES");
    }

    private static Action hopper(ActionType type, int x, int y, int z, int slot, long pair) {
        return hopperWithId(-1L, type, x, y, z, slot, pair);
    }

    private static Action hopperWithId(long id, ActionType type, int x, int y, int z, int slot, long pair) {
        return new ActionBuilder()
                .id(id)
                .timestamp(1_700_000_000_000L + id)
                .type(type)
                .actorName(Sentinel.HOPPER)
                .worldId(WORLD)
                .position(x, y, z)
                .targetId("minecraft:diamond_sword")
                .amount(1)
                .sourceTag(Sentinel.HOPPER)
                .oldBlockState("facing=down,enabled=true")
                .newBlockState("facing=down,enabled=true")
                .itemNbt(SWORD)
                .inventorySlot(slot)
                .pairId(pair)
                .build();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
            if (dir == null) {
                return null;
            }
        }
        return null;
    }

    static class RecordingMutator implements WorldMutator {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                              String targetMeta, byte[] itemNbt, Integer inventorySlot) {
            calls.add("remove|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + inventorySlot);
            return true;
        }

        @Override
        public boolean tryAddToContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                         String targetMeta, byte[] itemNbt, Integer inventorySlot) {
            calls.add("add|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + inventorySlot);
            return true;
        }
    }
}
