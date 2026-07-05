package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.3.2 Y1 regression: end-to-end round-trip through {@link RollbackEngine}
 * of NBT-carrying rows into the NBT-aware {@link WorldMutator} overloads.
 *
 * <p>The core has no Minecraft types, so we simulate a loader impl that
 * captures the NBT-aware overload calls. The key contract validated here is
 * that:
 * <ul>
 *   <li>Rows with {@code hasNbt() == true} land on the NBT-aware overloads,
 *       carrying the {@code oldBlockState}/{@code newBlockState}/{@code blockEntityNbt}/
 *       {@code itemNbt}/{@code entityNbt} payload by reference (no copies).</li>
 *   <li>Rows with {@code hasNbt() == false} land on the legacy overloads — no
 *       spurious null-NBT calls on the hot path.</li>
 * </ul>
 *
 * <p>Scenarios (matches the wave prompt's regression triple):
 * <ol>
 *   <li>waterlogged fence — BLOCK_BREAK with block-state property string;</li>
 *   <li>chest with contents — BLOCK_BREAK with blockEntityNbt;</li>
 *   <li>named+enchanted sword — HOPPER_PULL with itemNbt;</li>
 *   <li>tamed dog — ENTITY_KILL with entityNbt.</li>
 * </ol>
 */
class NbtRollbackRoundTripTest {

    private GuardianDao dao;
    private NbtCapturingMutator mutator;
    private RollbackEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(GuardianDao.class);
        mutator = new NbtCapturingMutator();
        Executor sync = Runnable::run;
        engine = new RollbackEngine(dao, mutator, sync);
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(1L);
    }

    @Test
    void waterlogged_fence_break_routes_through_nbt_setBlock_overload() throws Exception {
        Action a = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(10, 64, 20)
                .targetId("minecraft:oak_fence")
                .oldBlockState("waterlogged=true,north=true")
                .build();
        assertThat(a.hasNbt()).isTrue();

        givenPage(a);
        engine.rollback(rangeFilter(), false);

        assertThat(mutator.nbtSetBlock).hasSize(1);
        assertThat(mutator.legacySetBlock).isEmpty();
        NbtCapturingMutator.SetBlockCall c = mutator.nbtSetBlock.get(0);
        assertThat(c.targetId).isEqualTo("minecraft:oak_fence");
        assertThat(c.blockState).isEqualTo("waterlogged=true,north=true");
        assertThat(c.blockEntityNbt).isNull();
    }

    @Test
    void chest_break_with_be_nbt_routes_through_nbt_setBlock_overload() throws Exception {
        byte[] beNbt = "CHEST_CONTENTS_NBT".getBytes();
        Action a = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 64, 0)
                .targetId("minecraft:chest")
                .blockEntityNbt(beNbt)
                .build();
        assertThat(a.hasNbt()).isTrue();

        givenPage(a);
        engine.rollback(rangeFilter(), false);

        assertThat(mutator.nbtSetBlock).hasSize(1);
        assertThat(mutator.legacySetBlock).isEmpty();
        // Byte[] identity: RollbackEngine must NOT copy the payload.
        assertThat(mutator.nbtSetBlock.get(0).blockEntityNbt).isSameAs(beNbt);
    }

    @Test
    void named_enchanted_sword_hopper_pull_routes_through_nbt_giveOrDrop_overload() throws Exception {
        // ITEM_DROP/ITEM_PICKUP rows are audit-only until item-entity identity is tracked.
        // HOPPER_PULL still exercises the NBT-aware item give/drop rollback path.
        byte[] itemNbt = "NAMED_ENCHANTED_SWORD".getBytes();
        Action a = new ActionBuilder()
                .type(ActionType.HOPPER_PULL)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(1, 64, 1)
                .targetId("minecraft:netherite_sword")
                .amount(1)
                .itemNbt(itemNbt)
                .build();
        assertThat(a.hasNbt()).isTrue();

        givenPage(a);
        engine.rollback(rangeFilter(), false);

        assertThat(mutator.nbtGiveOrDrop).hasSize(1);
        assertThat(mutator.legacyGiveOrDrop).isEmpty();
        assertThat(mutator.nbtGiveOrDrop.get(0).itemNbt).isSameAs(itemNbt);
    }

    @Test
    void tamed_dog_entity_kill_routes_through_nbt_respawnEntity_overload() throws Exception {
        byte[] entNbt = "TAMED_WOLF_OWNER_UUID".getBytes();
        Action a = new ActionBuilder()
                .type(ActionType.ENTITY_KILL)
                .worldId("minecraft:overworld")
                .actorName("#creeper")
                .position(5, 64, 5)
                .targetId("minecraft:wolf")
                .entityNbt(entNbt)
                .build();
        assertThat(a.hasNbt()).isTrue();

        givenPage(a);
        engine.rollback(rangeFilter(), false);

        assertThat(mutator.nbtRespawnEntity).hasSize(1);
        assertThat(mutator.legacyRespawnEntity).isEmpty();
        assertThat(mutator.nbtRespawnEntity.get(0).entityNbt).isSameAs(entNbt);
    }

    @Test
    void row_without_nbt_lands_on_legacy_overload_no_nbt_call() throws Exception {
        Action a = new ActionBuilder()
                .type(ActionType.BLOCK_BREAK)
                .worldId("minecraft:overworld")
                .actorName("Notch")
                .position(0, 64, 0)
                .targetId("minecraft:stone")
                .build();
        assertThat(a.hasNbt()).isFalse();

        givenPage(a);
        engine.rollback(rangeFilter(), false);

        // NBT overload MUST NOT fire — this is the toggle-off hot-path invariant.
        assertThat(mutator.nbtSetBlock).isEmpty();
        assertThat(mutator.legacySetBlock).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private void givenPage(Action a) throws Exception {
        when(dao.query(any(), anyInt(), anyInt())).thenReturn(List.of(a)).thenReturn(List.of());
        when(dao.openRollbackBatch(any(), anyInt(), any(), any())).thenReturn(1L);
    }

    private QueryFilter rangeFilter() {
        return QueryFilter.builder()
                .sinceMillis(System.currentTimeMillis() - 3_600_000L)
                .build();
    }

    /**
     * Mutator that separately records legacy vs NBT-aware overload dispatch,
     * so tests can prove the RollbackEngine routes NBT-carrying rows to the
     * NBT overloads.
     */
    static final class NbtCapturingMutator implements WorldMutator {
        static final class SetBlockCall {
            final String targetId;
            final String targetMeta;
            final String blockState;
            final byte[] blockEntityNbt;
            SetBlockCall(String id, String meta, String s, byte[] nbt) {
                this.targetId = id; this.targetMeta = meta; this.blockState = s; this.blockEntityNbt = nbt;
            }
        }
        static final class GiveOrDropCall {
            final String itemId;
            final int amount;
            final byte[] itemNbt;
            GiveOrDropCall(String id, int amt, byte[] nbt) { this.itemId = id; this.amount = amt; this.itemNbt = nbt; }
        }
        static final class RespawnEntityCall {
            final String entityType;
            final byte[] entityNbt;
            RespawnEntityCall(String t, byte[] nbt) { this.entityType = t; this.entityNbt = nbt; }
        }

        final List<SetBlockCall> legacySetBlock = Collections.synchronizedList(new ArrayList<>());
        final List<SetBlockCall> nbtSetBlock    = Collections.synchronizedList(new ArrayList<>());
        final List<GiveOrDropCall> legacyGiveOrDrop = Collections.synchronizedList(new ArrayList<>());
        final List<GiveOrDropCall> nbtGiveOrDrop    = Collections.synchronizedList(new ArrayList<>());
        final List<RespawnEntityCall> legacyRespawnEntity = Collections.synchronizedList(new ArrayList<>());
        final List<RespawnEntityCall> nbtRespawnEntity    = Collections.synchronizedList(new ArrayList<>());

        @Override public void setBlock(String w, int x, int y, int z, String targetId, String targetMeta) {
            legacySetBlock.add(new SetBlockCall(targetId, targetMeta, null, null));
        }
        @Override public void setBlock(String w, int x, int y, int z, String targetId, String targetMeta,
                                       String blockState, byte[] blockEntityNbt) {
            nbtSetBlock.add(new SetBlockCall(targetId, targetMeta, blockState, blockEntityNbt));
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String itemId, int amt, String meta) {
            legacyGiveOrDrop.add(new GiveOrDropCall(itemId, amt, null));
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String itemId, int amt, String meta, byte[] itemNbt) {
            nbtGiveOrDrop.add(new GiveOrDropCall(itemId, amt, itemNbt));
        }
        @Override public void removeFromContainer(String w, int x, int y, int z, String itemId, int amt) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String entityType, String targetMeta) {
            legacyRespawnEntity.add(new RespawnEntityCall(entityType, null));
        }
        @Override public void respawnEntity(String w, int x, int y, int z, String entityType, String targetMeta, byte[] entityNbt) {
            nbtRespawnEntity.add(new RespawnEntityCall(entityType, entityNbt));
        }
    }
}
