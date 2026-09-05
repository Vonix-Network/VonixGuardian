package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.core.query.InspectorLookup;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 1: v8-compatible chest/container fidelity round-trip.
 *
 * <p>Traces one stateful container deposit through {@link Action}, existing
 * schema-v8 columns, DAO query, inspector visibility, preview, rollback, and
 * restore. Historical 2.0.1 rows must still read. A new schema version is not
 * required: slot identity reuses {@code inventory_slot} and payloads reuse
 * the v5 NBT columns.
 */
class BlockStateRoundTripTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000121");
    private static final String WORLD = "minecraft:overworld";
    private static final String CHEST_STATE = "facing=north,type=single,waterlogged=true";
    private static final byte[] CHEST_BE = utf8("{Items:[{Slot:7b,id:\"minecraft:diamond_sword\",count:1}]}");
    private static final byte[] SWORD_NBT = utf8("{id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:custom_name\":\"Excalibur\"}}");

    private SqliteDao dao;
    private SlotCapturingMutator mutator;
    private RollbackEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dao = new SqliteDao("jdbc:sqlite::memory:");
        dao.init();
        mutator = new SlotCapturingMutator();
        Executor sync = Runnable::run;
        engine = new RollbackEngine(dao, mutator, sync);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dao != null) {
            dao.close();
        }
    }

    @Test
    void containerDeposit_roundTripsBlockStateNbtSlotAndPairThroughV8Dao() throws Exception {
        long pair = 8_008L;
        Action written = deposit(21L, pair);

        assertThat(written.oldBlockState()).isEqualTo(CHEST_STATE);
        assertThat(written.newBlockState()).isEqualTo(CHEST_STATE);
        assertThat(written.blockEntityNbt()).isEqualTo(CHEST_BE);
        assertThat(written.itemNbt()).isEqualTo(SWORD_NBT);
        assertThat(written.inventorySlot()).isEqualTo(7);
        assertThat(written.pairId()).isEqualTo(pair);
        assertThat(written.sourceTag()).isEqualTo("#player");

        dao.insertBatch(List.of(written));
        Action read = dao.query(QueryFilter.empty(), 0, 8).get(0);

        assertThat(read.oldBlockState()).isEqualTo(CHEST_STATE);
        assertThat(read.newBlockState()).isEqualTo(CHEST_STATE);
        assertThat(read.blockEntityNbt()).containsExactly(CHEST_BE);
        assertThat(read.itemNbt()).containsExactly(SWORD_NBT);
        assertThat(read.inventorySlot()).isEqualTo(7);
        assertThat(read.pairId()).isEqualTo(pair);
        assertThat(read.type()).isEqualTo(ActionType.CONTAINER_DEPOSIT);
        assertThat(read.targetId()).isEqualTo("minecraft:diamond_sword");
        assertThat(read.amount()).isEqualTo(1);
        assertThat(read.actorName()).isEqualTo("Notch");
        assertThat(read.sourceTag()).isEqualTo("#player");
    }

    @Test
    void historicalV201Row_stillReadsWithNullFidelityColumns() throws Exception {
        Action legacy = new Action(-1L, 1_700_000_000_000L, ActionType.CONTAINER_DEPOSIT,
                ACTOR, "Notch", WORLD, 8, 64, 8,
                "minecraft:dirt", null, 4, false, null);
        dao.insertBatch(List.of(legacy));

        Action read = dao.query(QueryFilter.empty(), 0, 2).get(0);
        assertThat(read.type()).isEqualTo(ActionType.CONTAINER_DEPOSIT);
        assertThat(read.targetId()).isEqualTo("minecraft:dirt");
        assertThat(read.amount()).isEqualTo(4);
        assertThat(read.oldBlockState()).isNull();
        assertThat(read.newBlockState()).isNull();
        assertThat(read.blockEntityNbt()).isNull();
        assertThat(read.itemNbt()).isNull();
        assertThat(read.inventorySlot()).isNull();
        assertThat(read.pairId()).isNull();
        assertThat(read.hasNbt()).isFalse();
    }

    @Test
    void oversizedItemNbt_isRejectedRatherThanTruncated() {
        byte[] oversized = new byte[NbtPayload.MAX_BYTES + 1];
        oversized[0] = 7;
        Action a = new ActionBuilder()
                .type(ActionType.CONTAINER_DEPOSIT)
                .worldId(WORLD)
                .actorName("Notch")
                .targetId("minecraft:diamond_sword")
                .itemNbt(oversized)
                .inventorySlot(3)
                .build();
        assertThat(a.itemNbt()).isSameAs(oversized);
        assertThat(a.hasOversizedNbt()).isTrue();
        assertThat(NbtPayload.tooLarge(a.itemNbt())).isTrue();
        assertThat(NbtPayload.admit(oversized)).isNull();
        assertThat(NbtPayload.admit(SWORD_NBT)).containsExactly(SWORD_NBT);
    }

    @Test
    void exactCapNbt_isAdmissibleForItemBlockEntityAndEntity() {
        byte[] exactItem = filled(NbtPayload.MAX_BYTES, (byte) 1);
        byte[] exactBe = filled(NbtPayload.MAX_BYTES, (byte) 2);
        byte[] exactEntity = filled(NbtPayload.MAX_BYTES, (byte) 3);
        Action a = new ActionBuilder()
                .type(ActionType.CONTAINER_DEPOSIT)
                .worldId(WORLD)
                .actorName("Notch")
                .targetId("minecraft:diamond_sword")
                .itemNbt(exactItem)
                .blockEntityNbt(exactBe)
                .entityNbt(exactEntity)
                .inventorySlot(3)
                .build();
        assertThat(a.itemNbt()).isSameAs(exactItem);
        assertThat(a.blockEntityNbt()).isSameAs(exactBe);
        assertThat(a.entityNbt()).isSameAs(exactEntity);
        assertThat(a.hasOversizedNbt()).isFalse();
        assertThat(NbtPayload.tooLarge(exactItem)).isFalse();
        assertThat(NbtPayload.admit(exactItem)).isSameAs(exactItem);
    }

    @Test
    void oversizedBlockEntityAndEntityNbt_remainDistinguishableFromNull() {
        byte[] oversizedBe = filled(NbtPayload.MAX_BYTES + 1, (byte) 4);
        byte[] oversizedEntity = filled(NbtPayload.MAX_BYTES + 1, (byte) 5);
        Action be = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId(WORLD)
                .actorName("Notch")
                .targetId("minecraft:chest")
                .blockEntityNbt(oversizedBe)
                .build();
        Action entity = new ActionBuilder()
                .type(ActionType.ENTITY_KILL)
                .worldId(WORLD)
                .actorName("Notch")
                .targetId("minecraft:wolf")
                .entityNbt(oversizedEntity)
                .build();
        assertThat(be.blockEntityNbt()).isSameAs(oversizedBe);
        assertThat(be.hasOversizedNbt()).isTrue();
        assertThat(entity.entityNbt()).isSameAs(oversizedEntity);
        assertThat(entity.hasOversizedNbt()).isTrue();
    }

    @Test
    void genuineNullAndEmptyNbt_areNotOversized() {
        Action absent = new ActionBuilder()
                .type(ActionType.CONTAINER_DEPOSIT)
                .worldId(WORLD)
                .actorName("Notch")
                .targetId("minecraft:dirt")
                .build();
        byte[] empty = new byte[0];
        Action emptyPayload = new ActionBuilder()
                .type(ActionType.CONTAINER_DEPOSIT)
                .worldId(WORLD)
                .actorName("Notch")
                .targetId("minecraft:dirt")
                .itemNbt(empty)
                .blockEntityNbt(empty)
                .entityNbt(empty)
                .build();
        assertThat(absent.itemNbt()).isNull();
        assertThat(absent.blockEntityNbt()).isNull();
        assertThat(absent.entityNbt()).isNull();
        assertThat(absent.hasOversizedNbt()).isFalse();
        assertThat(absent.hasNbt()).isFalse();
        assertThat(emptyPayload.itemNbt()).isSameAs(empty);
        assertThat(emptyPayload.hasOversizedNbt()).isFalse();
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        bytes[0] = value;
        return bytes;
    }

    @Test
    void queryAndInspectorExposeExactSlot() throws Exception {
        dao.insertBatch(List.of(deposit(22L, 9L)));
        Action read = dao.query(QueryFilter.empty(), 0, 2).get(0);
        assertThat(read.inventorySlot()).isEqualTo(7);

        List<String> lines = InspectorLookup.lookup(dao, WORLD, 8, 64, 8, 10, read.timestamp() + 1_000L);
        assertThat(lines.get(1)).contains("slot=7");
        assertThat(lines.get(1)).contains("minecraft:diamond_sword");
        assertThat(lines.get(1)).contains("deposited");
    }

    @Test
    void previewDoesNotMutateThenRollbackUsesExactSlotAndRestoreReapplies() throws Exception {
        Action written = deposit(30L, 11L);
        dao.insertBatch(List.of(written));
        Action stored = dao.query(QueryFilter.empty(), 0, 2).get(0);
        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();

        RollbackResult preview = engine.rollback(filter, true);
        assertThat(preview.preview()).isTrue();
        assertThat(preview.affectedIds()).contains(stored.id());
        assertThat(mutator.calls).isEmpty();

        engine.rollback(filter, false);
        assertThat(mutator.calls).containsExactly(
                "removeContainer|" + WORLD + "|8|64|8|minecraft:diamond_sword|1|"
                        + SWORD_NBT.length + "|7");

        dao.markRolledBack(List.of(stored.id()), true);
        mutator.calls.clear();
        engine.restore(filter, false);
        assertThat(mutator.calls).containsExactly(
                "addContainer|" + WORLD + "|8|64|8|minecraft:diamond_sword|1|"
                        + SWORD_NBT.length + "|7");
    }

    @Test
    void slotAwareContainerRollbackFailsClosedWhenMutatorCannotApply() throws Exception {
        dao.insertBatch(List.of(deposit(31L, 12L)));
        Action stored = dao.query(QueryFilter.empty(), 0, 2).get(0);
        SlotCapturingMutator failing = new SlotCapturingMutator() {
            @Override
            public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                                  String targetMeta, byte[] itemNbt, Integer inventorySlot) {
                super.tryRemoveFromContainer(worldId, x, y, z, itemId, amount, targetMeta, itemNbt, inventorySlot);
                return false;
            }
        };
        RollbackEngine closed = new RollbackEngine(dao, failing, Runnable::run);
        QueryFilter filter = QueryFilter.builder().sinceMillis(1L).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> closed.rollbackAsync(filter, false).toCompletableFuture().join())
                .hasCauseInstanceOf(RollbackMutationException.class);
        assertThat(dao.query(QueryFilter.empty(), 0, 2).get(0).rolledBack()).isFalse();
        assertThat(dao.findRepairRequired()).isEmpty();
    }

    private static Action deposit(long timestampOffset, long pair) {
        return new ActionBuilder()
                .id(-1L)
                .timestamp(1_700_000_000_000L + timestampOffset)
                .type(ActionType.CONTAINER_DEPOSIT)
                .actorUuid(ACTOR)
                .actorName("Notch")
                .worldId(WORLD)
                .position(8, 64, 8)
                .targetId("minecraft:diamond_sword")
                .amount(1)
                .sourceTag("#player")
                .oldBlockState(CHEST_STATE)
                .newBlockState(CHEST_STATE)
                .blockEntityNbt(CHEST_BE)
                .itemNbt(SWORD_NBT)
                .inventorySlot(7)
                .pairId(pair)
                .build();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static class SlotCapturingMutator implements WorldMutator {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {
            calls.add("removeContainerLegacy|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount);
            return true;
        }

        @Override
        public boolean tryRemoveFromContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                              String targetMeta, byte[] itemNbt, Integer inventorySlot) {
            calls.add("removeContainer|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount
                    + "|" + (itemNbt == null ? 0 : itemNbt.length) + "|" + inventorySlot);
            return true;
        }

        @Override
        public boolean tryAddToContainer(String worldId, int x, int y, int z, String itemId, int amount,
                                         String targetMeta, byte[] itemNbt, Integer inventorySlot) {
            calls.add("addContainer|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount
                    + "|" + (itemNbt == null ? 0 : itemNbt.length) + "|" + inventorySlot);
            return true;
        }

        @Override
        public boolean tryGiveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {
            calls.add("giveOrDrop|" + worldId + "|" + x + "|" + y + "|" + z + "|" + itemId + "|" + amount);
            return true;
        }
    }
}
