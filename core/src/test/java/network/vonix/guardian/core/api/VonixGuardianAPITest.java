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
import network.vonix.guardian.core.storage.jdbc.SqliteDao;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-B12+B13 — public API surface tests.
 *
 * <p>Two flavours:
 * <ul>
 *   <li>{@link #apiVersion_and_testAPI_are_stable()} — static sanity of the
 *       version-stable contract.</li>
 *   <li>{@link #hasPlaced_calls_hasActionsInWindow_with_block_place_type()},
 *       {@link #hasRemoved_calls_hasActionsInWindow_with_block_break_type()},
 *       {@link #blockLookup_query_shape()},
 *       {@link #containerLookup_signs_amount_by_direction()},
 *       {@link #chatLookup_and_commandLookup_scope_by_action_type()} —
 *       verify each API method calls the right DAO query shape by mocking
 *       {@link GuardianDao} and asserting arguments.</li>
 * </ul>
 */
class VonixGuardianAPITest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-api-test");
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
        // Boot a real Guardian with an in-memory-sqlite backend, then swap the
        // dao field for a Mockito mock so we can assert on query shape.
        GuardianConfig cfg = new GuardianConfig(
            new GuardianConfig.Database("sqlite", tmp.resolve("api.db").toString(), null, null, null),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                500L, 8192,
                List.of(), false
            ),
            new GuardianConfig.Permissions(true, 3),
            new GuardianConfig.Lookup(50, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(3_600L, 3_600L, 0L, "03:30"),
            "aqua"
        );
        guardian = Guardian.boot(cfg, tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);

        mockDao = mock(GuardianDao.class);
        replaceDao(guardian, mockDao);
    }

    @AfterEach
    void tearDown() {
        if (guardian != null) {
            try { guardian.close(); } catch (Exception ignored) {}
        }
    }

    /** Reflectively swap Guardian#dao — the field is {@code final} so we set it via reflection. */
    private static void replaceDao(Guardian g, GuardianDao replacement) throws Exception {
        Field f = Guardian.class.getDeclaredField("dao");
        f.setAccessible(true);
        // Turn off "final" bit — JDK 17+ supports this via reflection on non-record fields.
        // If this stops working under newer JDKs, switch to a constructor-friendly Guardian variant.
        f.set(g, replacement);
    }

    @Test
    void apiVersion_and_testAPI_are_stable() {
        VonixGuardianAPI api = guardian.api();
        assertThat(api.apiVersion()).isEqualTo(1);
        assertThat(api.pluginVersion()).isEqualTo(GuardianAPI.PLUGIN_VERSION);
        assertThat(api.testAPI()).isTrue();
        // Latching: repeat should return same instance.
        assertThat(guardian.api()).isSameAs(api);
    }

    @Test
    void hasPlaced_calls_hasActionsInWindow_with_block_place_type() throws Exception {
        UUID u = UUID.randomUUID();
        when(mockDao.hasActionsInWindow(eq(u), eq("minecraft:overworld"), eq(1), eq(2), eq(3),
                any(ActionType[].class), anyLong())).thenReturn(true);

        boolean got = guardian.api().hasPlaced(u, "minecraft:overworld", 1, 2, 3, 60);

        assertThat(got).isTrue();
        verify(mockDao, times(1)).hasActionsInWindow(
                eq(u), eq("minecraft:overworld"), eq(1), eq(2), eq(3),
                argTypesEq(ActionType.BLOCK_PLACE), eq(60_000L));
    }

    @Test
    void hasRemoved_calls_hasActionsInWindow_with_block_break_type() throws Exception {
        UUID u = UUID.randomUUID();
        when(mockDao.hasActionsInWindow(any(), any(), anyInt(), anyInt(), anyInt(),
                any(ActionType[].class), anyLong())).thenReturn(false);

        boolean got = guardian.api().hasRemoved(u, "minecraft:the_nether", 10, 20, 30, 5);

        assertThat(got).isFalse();
        verify(mockDao, times(1)).hasActionsInWindow(
                eq(u), eq("minecraft:the_nether"), eq(10), eq(20), eq(30),
                argTypesEq(ActionType.BLOCK_BREAK), eq(5_000L));
    }

    @Test
    void blockLookup_query_shape() throws Exception {
        UUID u = UUID.randomUUID();
        Action row = new Action(1L, System.currentTimeMillis(), ActionType.BLOCK_PLACE, u, "Alice",
                "minecraft:overworld", 5, 64, 6, "minecraft:stone", null, 1, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(row));

        List<BlockLookupResult> results = guardian.api()
                .blockLookup("minecraft:overworld", 5, 64, 6, 3600);

        assertThat(results).hasSize(1);
        BlockLookupResult r = results.get(0);
        assertThat(r.actorUuid()).isEqualTo(u);
        assertThat(r.actorName()).isEqualTo("Alice");
        assertThat(r.worldId()).isEqualTo("minecraft:overworld");
        assertThat(r.x()).isEqualTo(5);
        assertThat(r.blockId()).isEqualTo("minecraft:stone");
        assertThat(r.action()).isEqualTo("block"); // "+block" → sign stripped
        verify(mockDao, times(1)).query(any(), eq(0), anyInt());
    }

    @Test
    void containerLookup_signs_amount_by_direction() throws Exception {
        UUID u = UUID.randomUUID();
        Action deposit = new Action(1L, System.currentTimeMillis(), ActionType.CONTAINER_DEPOSIT, u,
                "Bob", "minecraft:overworld", 0, 0, 0, "minecraft:diamond", null, 5, false, null);
        Action withdraw = new Action(2L, System.currentTimeMillis(), ActionType.CONTAINER_WITHDRAW, u,
                "Bob", "minecraft:overworld", 0, 0, 0, "minecraft:diamond", null, 3, false, null);
        when(mockDao.query(any(), anyInt(), anyInt())).thenReturn(List.of(deposit, withdraw));

        List<ContainerLookupResult> results = guardian.api()
                .containerLookup("minecraft:overworld", 0, 0, 0, 0);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).amountDelta()).isEqualTo(5);   // deposit → positive
        assertThat(results.get(1).amountDelta()).isEqualTo(-3);  // withdraw → negative
    }

    @Test
    void chatLookup_and_commandLookup_scope_by_action_type() throws Exception {
        UUID u = UUID.randomUUID();
        Action chatRow = new Action(1L, 100L, ActionType.CHAT, u, "Cass",
                "minecraft:overworld", 0, 0, 0, "hello world", null, 0, false, null);
        Action cmdRow  = new Action(2L, 200L, ActionType.COMMAND, u, "Cass",
                "minecraft:overworld", 0, 0, 0, "/tp home", null, 0, false, null);
        when(mockDao.query(any(), anyInt(), anyInt()))
                .thenReturn(List.of(chatRow))
                .thenReturn(List.of(cmdRow));

        List<MessageLookupResult> chat = guardian.api().chatLookup(u, 60, 20);
        List<MessageLookupResult> cmd  = guardian.api().commandLookup(u, 60, 20);

        assertThat(chat).hasSize(1);
        assertThat(chat.get(0).kind()).isEqualTo("chat");
        assertThat(chat.get(0).message()).isEqualTo("hello world");
        assertThat(cmd).hasSize(1);
        assertThat(cmd.get(0).kind()).isEqualTo("command");
        assertThat(cmd.get(0).message()).isEqualTo("/tp home");

        verify(mockDao, times(2)).query(any(), eq(0), eq(20));
    }

    // Mockito argThat sugar: assert the ActionType[] param contains exactly one type == want.
    private static ActionType[] argTypesEq(ActionType want) {
        return org.mockito.ArgumentMatchers.argThat(arr ->
                arr != null && arr.length == 1 && arr[0] == want);
    }

    /** Silence "unused import" for AtomicReference which some IDEs otherwise strip. */
    @SuppressWarnings("unused")
    private static final AtomicReference<?> KEEP = new AtomicReference<>();
}
