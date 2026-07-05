/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core.api;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import network.vonix.guardian.core.storage.GuardianDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5-03 — CoreProtect-parity API surface (v1.2.0).
 *
 * <p>Covers the new lookup methods ({@code itemLookup}, {@code inventoryLookup},
 * {@code sessionLookup}, {@code usernameLookup}, {@code signLookup},
 * {@code queueLookup}) and the direct-log methods
 * ({@code logChat}, {@code logCommand}, {@code logInteraction},
 * {@code logPlacement}, {@code logRemoval}).
 */
class GuardianAPIExtendedTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-api-ext-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {}
        @Override public void giveOrDrop(String w, int x, int y, int z, String i, int a, String m) {}
        @Override public void removeFromContainer(String w, int x, int y, int z, String i, int a) {}
        @Override public void respawnEntity(String w, int x, int y, int z, String e, String m) {}
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;

    @TempDir
    Path tmp;

    private Guardian guardian;
    private GuardianDao mockDao;

    @BeforeEach
    void setUp() throws Exception {
        GuardianConfig cfg = new GuardianConfig(
            new GuardianConfig.Database("sqlite", tmp.resolve("api-ext.db").toString(), null, null, null, null, GuardianConfig.Hikari.defaults()),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30, true),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                500L, 8192,
                List.of(), false
            ,
            true,
            true,
            true,
            true,
            true,
            true,
            false,
            false,
            true,
            true,
            false,
            true,
            false,
            true),
            new GuardianConfig.Permissions(true, 3, java.util.Map.of()),
            new GuardianConfig.Lookup(50, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(3_600L, 3_600L, 0L, "03:30"),
        GuardianConfig.Storage.defaults(),
        GuardianConfig.Rollback.defaults(),
            "aqua"
        ,
        "en_us");
        guardian = Guardian.boot(cfg, tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);

        mockDao = mock(GuardianDao.class);
        Field f = Guardian.class.getDeclaredField("dao");
        f.setAccessible(true);
        f.set(guardian, mockDao);
    }

    @AfterEach
    void tearDown() {
        if (guardian != null) {
            try { guardian.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void itemLookup_returns_typed_records_and_strips_sign() throws Exception {
        UUID u = UUID.randomUUID();
        Action drop = new Action(1L, 100L, ActionType.ITEM_DROP, u, "Alice",
                "minecraft:overworld", 0, 64, 0, "minecraft:diamond", null, 3, false, "drop:death");
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(drop));

        List<ItemLookupResult> out = guardian.api().itemLookup(u, 60, 10);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).itemId()).isEqualTo("minecraft:diamond");
        assertThat(out.get(0).amount()).isEqualTo(3);
        // "-item" → "item"
        assertThat(out.get(0).action()).isEqualTo("item");
        assertThat(out.get(0).sourceTag()).isEqualTo("drop:death");
        verify(mockDao).query(any(), eq(0), eq(10));
    }

    @Test
    void inventoryLookup_covers_deposit_and_withdraw() throws Exception {
        UUID u = UUID.randomUUID();
        Action dep = new Action(1L, 100L, ActionType.INVENTORY_DEPOSIT, u, "Bob",
                "minecraft:overworld", 0, 0, 0, "minecraft:iron_ingot", null, 5, false, null);
        Action wdr = new Action(2L, 200L, ActionType.INVENTORY_WITHDRAW, u, "Bob",
                "minecraft:overworld", 0, 0, 0, "minecraft:iron_ingot", null, 2, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(dep, wdr));

        List<InventoryLookupResult> out = guardian.api().inventoryLookup(u, 0, 0);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).action()).isEqualTo("inventory");
        assertThat(out.get(1).action()).isEqualTo("inventory");
    }

    @Test
    void sessionLookup_marks_direction() throws Exception {
        UUID u = UUID.randomUUID();
        Action join = new Action(1L, 100L, ActionType.SESSION_JOIN, u, "Cass",
                "minecraft:overworld", 0, 0, 0, "127.0.0.1", null, 0, false, null);
        Action leave = new Action(2L, 200L, ActionType.SESSION_LEAVE, u, "Cass",
                "minecraft:overworld", 0, 0, 0, "quit", null, 0, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(join, leave));

        List<SessionLookupResult> out = guardian.api().sessionLookup(u, 3600, 20);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).direction()).isEqualTo("join");
        assertThat(out.get(0).targetMeta()).isEqualTo("127.0.0.1");
        assertThat(out.get(1).direction()).isEqualTo("leave");
        assertThat(out.get(1).targetMeta()).isEqualTo("quit");
    }

    @Test
    void usernameLookup_extracts_previous_name_from_target() throws Exception {
        UUID u = UUID.randomUUID();
        Action change = new Action(1L, 100L, ActionType.USERNAME_CHANGE, u, "NewName",
                "minecraft:overworld", 0, 0, 0, "OldName -> NewName", null, 0, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(change));

        List<UsernameLookupResult> out = guardian.api().usernameLookup(u, 0, 10);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).actorName()).isEqualTo("NewName");
        assertThat(out.get(0).previousName()).isEqualTo("OldName");
    }

    @Test
    void usernameLookup_missing_arrow_yields_question_mark() throws Exception {
        UUID u = UUID.randomUUID();
        Action change = new Action(1L, 100L, ActionType.USERNAME_CHANGE, u, "NewName",
                "minecraft:overworld", 0, 0, 0, null, null, 0, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(change));

        assertThat(guardian.api().usernameLookup(u, 0, 10).get(0).previousName()).isEqualTo("?");
    }

    @Test
    void signLookup_returns_coord_scoped_sign_records() throws Exception {
        UUID u = UUID.randomUUID();
        Action sign = new Action(1L, 100L, ActionType.SIGN, u, "Dave",
                "minecraft:overworld", 10, 65, 20, "hello|world|line3|line4", null, 0, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(sign));

        List<SignLookupResult> out = guardian.api().signLookup("minecraft:overworld", 10, 65, 20, 3600);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).lines()).isEqualTo("hello|world|line3|line4");
        assertThat(out.get(0).x()).isEqualTo(10);
        assertThat(out.get(0).y()).isEqualTo(65);
        assertThat(out.get(0).z()).isEqualTo(20);
    }

    @Test
    void lookupDtosExposePersistedNbtAndSignMetadata() throws Exception {
        UUID u = UUID.randomUUID();
        byte[] be = "CHEST_NBT".getBytes();
        byte[] item = "ITEM_NBT".getBytes();

        Action block = new Action(-1L, 100L, ActionType.BLOCK_BREAK, u, "Alice",
                "minecraft:overworld", 10, 64, 20, "minecraft:chest", null, 1, false, "src",
                null, null, null,
                "facing=north", "minecraft:air", be, null, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(block));

        BlockLookupResult br = guardian.api().blockLookup("minecraft:overworld", 10, 64, 20, 60).get(0);
        assertThat(br.oldBlockState()).isEqualTo("facing=north");
        assertThat(br.newBlockState()).isEqualTo("minecraft:air");
        assertThat(br.blockEntityNbt()).isSameAs(be);

        Action container = new Action(-1L, 101L, ActionType.CONTAINER_DEPOSIT, u, "Alice",
                "minecraft:overworld", 10, 64, 20, "minecraft:diamond_sword", null, 1, false, "src",
                null, null, null,
                null, null, null, item, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(container));
        ContainerLookupResult cr = guardian.api().containerLookup("minecraft:overworld", 10, 64, 20, 60).get(0);
        assertThat(cr.itemNbt()).isSameAs(item);

        Action itemRow = new Action(-1L, 102L, ActionType.ITEM_DROP, u, "Alice",
                "minecraft:overworld", 0, 64, 0, "minecraft:diamond_sword", null, 1, false, "src",
                null, null, null,
                null, null, null, item, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(itemRow));
        ItemLookupResult ir = guardian.api().itemLookup(u, 60, 10).get(0);
        assertThat(ir.itemNbt()).isSameAs(item);

        Action sign = new Action(-1L, 103L, ActionType.SIGN, u, "Alice",
                "minecraft:overworld", 10, 64, 20, "hello", null, 0, false, "src",
                "back", "blue", true);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(sign));
        SignLookupResult sr = guardian.api().signLookup("minecraft:overworld", 10, 64, 20, 60).get(0);
        assertThat(sr.signSide()).isEqualTo("back");
        assertThat(sr.signDyeColor()).isEqualTo("blue");
        assertThat(sr.signWaxed()).isTrue();
    }

    @Test
    void queueLookup_filters_pending_queue_snapshot() {
        Action queued = new Action(-1L, 100L, ActionType.BLOCK_PLACE, UUID.randomUUID(), "Alice",
                "minecraft:overworld", 10, 65, 20, "minecraft:stone", null, 1, false, null);
        Action other = new Action(-1L, 100L, ActionType.BLOCK_PLACE, UUID.randomUUID(), "Bob",
                "minecraft:overworld", 11, 65, 20, "minecraft:dirt", null, 1, false, null);
        guardian.queue().setPaused(true);
        guardian.queue().submit(queued);
        guardian.queue().submit(other);

        assertThat(guardian.api().queueLookup("minecraft:overworld", 10, 65, 20))
            .containsExactly(queued);
    }

    @Test
    void logChat_and_logCommand_return_false_when_gate_rejects() {
        UUID u = UUID.randomUUID();
        guardian.gate().addHook(action -> network.vonix.guardian.core.event.EventHook.Decision.DENY);

        assertThat(guardian.api().logChat(u, "Alice", "minecraft:overworld", "hi")).isFalse();
        assertThat(guardian.api().logCommand(u, "Alice", "minecraft:overworld", "/tp home")).isFalse();
    }

    @Test
    void logPlacement_returns_false_when_queue_is_full() {
        UUID u = UUID.randomUUID();
        guardian.queue().setPaused(true);

        boolean sawRejected = false;
        for (int i = 0; i < 1_500; i++) {
            boolean accepted = guardian.api().logPlacement(u, "Alice", "minecraft:overworld", i, 64, 0, "minecraft:stone");
            if (!accepted) {
                sawRejected = true;
                break;
            }
        }

        assertThat(sawRejected).isTrue();
    }

    @Test
    void logChat_and_logCommand_go_through_submit_pipeline() {
        UUID u = UUID.randomUUID();
        long before = guardian.submitted();
        assertThat(guardian.api().logChat(u, "Alice", "minecraft:overworld", "hi")).isTrue();
        assertThat(guardian.api().logCommand(u, "Alice", "minecraft:overworld", "/tp home")).isTrue();
        // Both should have flowed through submit() and been counted.
        assertThat(guardian.submitted() - before).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void logInteraction_logPlacement_logRemoval_all_dispatch() {
        UUID u = UUID.randomUUID();
        long before = guardian.submitted();
        assertThat(guardian.api().logInteraction(u, "Alice", "minecraft:overworld", 1, 2, 3)).isTrue();
        assertThat(guardian.api().logPlacement(u, "Alice", "minecraft:overworld", 1, 2, 3, "minecraft:stone")).isTrue();
        assertThat(guardian.api().logRemoval(u, "Alice", "minecraft:overworld", 1, 2, 3, "minecraft:stone")).isTrue();
        assertThat(guardian.submitted() - before).isGreaterThanOrEqualTo(3L);
    }

    @Test
    void logPlacement_null_blockId_rejected() {
        UUID u = UUID.randomUUID();
        try {
            guardian.api().logPlacement(u, "Alice", "minecraft:overworld", 0, 0, 0, null);
            assertThat(false).as("expected NPE").isTrue();
        } catch (NullPointerException expected) {
            // ok
        }
    }
}
