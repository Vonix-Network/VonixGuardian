package network.vonix.guardian.core;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GuardianSubmitBoundaryTest {

    private static final Executor SYNC = Runnable::run;
    private static final OpLevelFallback ZERO_OP = uuid -> 0;
    private static final WorldMutator NOOP = new WorldMutator() {
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) {return true; }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String i, int a, String m) {return true; }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String i, int a) {return true; }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String e, String m) {return true; }
    };

    @Test
    void submitAcceptedSanitizesDirectlyConstructedCommandBeforePersistence(@TempDir Path tmp)
            throws Exception {
        Guardian guardian = boot(tmp, "boundary.db");
        try {
            UUID actor = UUID.randomUUID();
            Action direct = new Action(-1L, 123L, ActionType.COMMAND, actor, "Alice",
                    "minecraft:overworld", 1, 2, 3, "/give Alice minecraft:diamond 64",
                    "meta", 2, false, "source", null, null, null,
                    null, null, null, null, null);

            assertThat(guardian.submitAccepted(direct)).isTrue();
            List<Action> rows = awaitRows(guardian, 1);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).targetId()).isEqualTo("/give");
            assertThat(rows.get(0).targetMeta()).isEqualTo("meta");
            assertThat(rows.get(0).x()).isEqualTo(1);
        } finally {
            guardian.close();
        }
    }

    @Test
    void exactCapItemNbt_isAdmitted(@TempDir Path tmp) throws Exception {
        Guardian guardian = boot(tmp, "exact-cap.db");
        try {
            byte[] exact = filled(NbtPayload.MAX_BYTES, (byte) 9);
            Action a = container(ActionType.CONTAINER_DEPOSIT, exact, null, null);
            assertThat(a.hasOversizedNbt()).isFalse();
            assertThat(guardian.submitAccepted(a)).isTrue();
            List<Action> rows = awaitRows(guardian, 1);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).itemNbt()).hasSize(NbtPayload.MAX_BYTES);
        } finally {
            guardian.close();
        }
    }

    @Test
    void oversizedItemNbt_isRejectedWithNoDaoInsert(@TempDir Path tmp) throws Exception {
        assertOversizedRejected(tmp, "oversize-item.db",
                container(ActionType.CONTAINER_DEPOSIT, oversized((byte) 1), null, null));
    }

    @Test
    void oversizedBlockEntityNbt_isRejectedWithNoDaoInsert(@TempDir Path tmp) throws Exception {
        assertOversizedRejected(tmp, "oversize-be.db",
                container(ActionType.CONTAINER_DEPOSIT, new byte[]{1}, oversized((byte) 2), null));
    }

    @Test
    void oversizedEntityNbt_isRejectedWithNoDaoInsert(@TempDir Path tmp) throws Exception {
        Action a = new ActionBuilder()
                .type(ActionType.ENTITY_KILL)
                .actorName("Notch")
                .worldId("minecraft:overworld")
                .position(1, 64, 2)
                .targetId("minecraft:wolf")
                .entityNbt(oversized((byte) 3))
                .build();
        assertOversizedRejected(tmp, "oversize-entity.db", a);
    }

    @Test
    void directActionOversizedItemNbt_isRejectedBySubmitAccepted(@TempDir Path tmp) throws Exception {
        Guardian guardian = boot(tmp, "direct-oversize.db");
        try {
            byte[] oversized = oversized((byte) 7);
            Action direct = new Action(-1L, 123L, ActionType.CONTAINER_DEPOSIT,
                    UUID.fromString("00000000-0000-0000-0000-000000000121"), "Alice",
                    "minecraft:overworld", 1, 64, 2, "minecraft:diamond_sword",
                    null, 1, false, null, null, null, null,
                    null, null, null, oversized, null, null, 3);
            assertThat(direct.hasOversizedNbt()).isTrue();
            assertThat(direct.itemNbt()).isSameAs(oversized);
            long submittedBefore = guardian.submitted();
            assertThat(guardian.submitAccepted(direct)).isFalse();
            assertThat(guardian.submitted()).isEqualTo(submittedBefore);
            assertThat(awaitRows(guardian, 1)).isEmpty();
        } finally {
            guardian.close();
        }
    }

    @Test
    void pairWithOversizedFirstHalf_isRejectedAtomically(@TempDir Path tmp) throws Exception {
        assertOversizedPairRejected(tmp, "pair-first.db",
                inventory(ActionType.INVENTORY_WITHDRAW, oversized((byte) 8), 21L),
                inventory(ActionType.INVENTORY_DEPOSIT, new byte[]{2}, 21L));
    }

    @Test
    void pairWithOversizedSecondHalf_isRejectedAtomically(@TempDir Path tmp) throws Exception {
        assertOversizedPairRejected(tmp, "pair-second.db",
                inventory(ActionType.INVENTORY_WITHDRAW, new byte[]{1}, 22L),
                inventory(ActionType.INVENTORY_DEPOSIT, oversized((byte) 9), 22L));
    }

    @Test
    void genuineNullAndEmptyNbt_remainAccepted(@TempDir Path tmp) throws Exception {
        Guardian guardian = boot(tmp, "null-empty.db");
        try {
            Action absent = new ActionBuilder()
                    .type(ActionType.CONTAINER_DEPOSIT)
                    .actorName("Alice")
                    .worldId("minecraft:overworld")
                    .position(1, 64, 2)
                    .targetId("minecraft:dirt")
                    .amount(1)
                    .build();
            Action empty = new ActionBuilder()
                    .type(ActionType.CONTAINER_WITHDRAW)
                    .actorName("Alice")
                    .worldId("minecraft:overworld")
                    .position(1, 64, 2)
                    .targetId("minecraft:dirt")
                    .amount(1)
                    .itemNbt(new byte[0])
                    .inventorySlot(2)
                    .build();
            assertThat(absent.hasOversizedNbt()).isFalse();
            assertThat(empty.hasOversizedNbt()).isFalse();
            assertThat(guardian.submitAccepted(absent)).isTrue();
            assertThat(guardian.submitAccepted(empty)).isTrue();
            List<Action> rows = awaitRows(guardian, 2);
            assertThat(rows).hasSize(2);
        } finally {
            guardian.close();
        }
    }

    private static void assertOversizedRejected(Path tmp, String dbName, Action a) throws Exception {
        Guardian guardian = boot(tmp, dbName);
        try {
            assertThat(a.hasOversizedNbt()).isTrue();
            long submittedBefore = guardian.submitted();
            assertThat(guardian.submitAccepted(a)).isFalse();
            assertThat(guardian.submitted()).isEqualTo(submittedBefore);
            assertThat(awaitRows(guardian, 1)).isEmpty();
        } finally {
            guardian.close();
        }
    }

    private static void assertOversizedPairRejected(Path tmp, String dbName, Action first, Action second)
            throws Exception {
        Guardian guardian = boot(tmp, dbName);
        try {
            assertThat(first.hasOversizedNbt() || second.hasOversizedNbt()).isTrue();
            long submittedBefore = guardian.submitted();
            assertThat(guardian.submitAcceptedPair(first, second)).isFalse();
            assertThat(guardian.submitted()).isEqualTo(submittedBefore);
            assertThat(awaitRows(guardian, 1)).isEmpty();
        } finally {
            guardian.close();
        }
    }

    private static Guardian boot(Path tmp, String dbName) throws Exception {
        GuardianConfig defaults = GuardianConfig.defaults();
        GuardianConfig config = new GuardianConfig(
                new GuardianConfig.Database("sqlite", tmp.resolve(dbName).toString(),
                        null, null, null),
                new GuardianConfig.Queue(32, 25L, 4),
                new GuardianConfig.LogFile(false, "logs", true, 30, true),
                defaults.actions(), defaults.permissions(), defaults.lookup(), defaults.privacy(),
                defaults.purge(), defaults.storage(), defaults.rollback(), defaults.theme(),
                defaults.language());
        ThreadFactory threads = r -> {
            Thread t = new Thread(r, "vg-submit-boundary-test");
            t.setDaemon(true);
            return t;
        };
        return Guardian.boot(config, tmp, NOOP, ZERO_OP, SYNC, threads);
    }

    private static List<Action> awaitRows(Guardian guardian, int min) throws Exception {
        List<Action> rows = List.of();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (rows.size() < min && System.nanoTime() < deadline) {
            rows = guardian.dao().query(QueryFilter.empty(), 0, 10);
            if (rows.size() < min) Thread.sleep(10L);
        }
        return rows;
    }

    private static Action container(ActionType type, byte[] itemNbt, byte[] blockEntityNbt, byte[] entityNbt) {
        return new ActionBuilder()
                .type(type)
                .actorName("Alice")
                .worldId("minecraft:overworld")
                .position(1, 64, 2)
                .targetId("minecraft:diamond_sword")
                .amount(1)
                .itemNbt(itemNbt)
                .blockEntityNbt(blockEntityNbt)
                .entityNbt(entityNbt)
                .inventorySlot(7)
                .build();
    }

    private static Action inventory(ActionType type, byte[] itemNbt, long pairId) {
        return new ActionBuilder()
                .type(type)
                .actorUuid(UUID.fromString("00000000-0000-0000-0000-000000000041"))
                .actorName("Alice")
                .worldId("minecraft:overworld")
                .position(1, 64, 2)
                .targetId("minecraft:diamond")
                .amount(1)
                .itemNbt(itemNbt)
                .inventorySlot(3)
                .pairId(pairId)
                .build();
    }

    private static byte[] oversized(byte marker) {
        return filled(NbtPayload.MAX_BYTES + 1, marker);
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        bytes[0] = value;
        return bytes;
    }
}
