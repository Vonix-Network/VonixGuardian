package network.vonix.guardian.core.event;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
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

class InventoryPairAdmissionTest {

    private static final Executor SYNC = Runnable::run;
    private static final OpLevelFallback ZERO_OP = uuid -> 0;
    private static final WorldMutator NOOP = new WorldMutator() {
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) { return true; }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String i, int a, String m) { return true; }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String i, int a) { return true; }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String e, String m) { return true; }
    };

    @Test
    void replacementPersistsBothHalvesWithSharedPairId(@TempDir Path tmp) throws Exception {
        Guardian guardian = boot(tmp, 32);
        try {
            UUID actor = UUID.fromString("00000000-0000-0000-0000-000000000041");
            guardian.submitInventoryReplacement(actor, "Alice", "minecraft:overworld",
                    1, 64, 2,
                    "minecraft:diamond", 2, new byte[]{9},
                    "minecraft:emerald", 1, new byte[]{8},
                    7);

            List<Action> rows = awaitRows(guardian, 2);
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(Action::type)
                    .containsExactlyInAnyOrder(ActionType.INVENTORY_WITHDRAW, ActionType.INVENTORY_DEPOSIT);
            Long pair = rows.get(0).pairId();
            assertThat(pair).isNotNull().isNotZero();
            assertThat(rows.get(1).pairId()).isEqualTo(pair);
            assertThat(rows).allMatch(a -> Integer.valueOf(7).equals(a.inventorySlot()));
        } finally {
            guardian.close();
        }
    }

    @Test
    void maintenanceWriteBlockRejectsBothHalves(@TempDir Path tmp) throws Exception {
        Guardian guardian = boot(tmp, 32);
        try {
            assertThat(guardian.beginMaintenanceWriteBlock("test")).isTrue();
            UUID actor = UUID.randomUUID();
            Action withdraw = inventory(actor, ActionType.INVENTORY_WITHDRAW, 11L);
            Action deposit = inventory(actor, ActionType.INVENTORY_DEPOSIT, 11L);
            assertThat(guardian.submitAcceptedPair(withdraw, deposit)).isFalse();
            assertThat(awaitRows(guardian, 1)).isEmpty();
        } finally {
            guardian.endMaintenanceWriteBlock("test");
            guardian.close();
        }
    }

    @Test
    void gateDenialOfOneHalfDropsThePair(@TempDir Path tmp) throws Exception {
        Guardian guardian = boot(tmp, 32);
        try {
            guardian.gate().addHook(action -> action.type() == ActionType.INVENTORY_DEPOSIT
                    ? EventHook.Decision.DENY : EventHook.Decision.PASS);
            UUID actor = UUID.randomUUID();
            Action withdraw = inventory(actor, ActionType.INVENTORY_WITHDRAW, 12L);
            Action deposit = inventory(actor, ActionType.INVENTORY_DEPOSIT, 12L);
            assertThat(guardian.submitAcceptedPair(withdraw, deposit)).isFalse();
            assertThat(awaitRows(guardian, 1)).isEmpty();
        } finally {
            guardian.close();
        }
    }

    private static Guardian boot(Path tmp, int queueSize) throws Exception {
        GuardianConfig defaults = GuardianConfig.defaults();
        GuardianConfig config = new GuardianConfig(
                new GuardianConfig.Database("sqlite", tmp.resolve("pair.db").toString(),
                        null, null, null),
                new GuardianConfig.Queue(queueSize, 25L, 4),
                new GuardianConfig.LogFile(false, "logs", true, 30, true),
                defaults.actions(), defaults.permissions(), defaults.lookup(), defaults.privacy(),
                defaults.purge(), defaults.storage(), defaults.rollback(), defaults.theme(),
                defaults.language());
        ThreadFactory threads = r -> {
            Thread t = new Thread(r, "vg-pair-admission-test");
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

    private static Action inventory(UUID actor, ActionType type, long pairId) {
        return new network.vonix.guardian.core.action.ActionBuilder()
                .type(type)
                .actorUuid(actor)
                .actorName("Alice")
                .worldId("minecraft:overworld")
                .position(1, 64, 2)
                .targetId("minecraft:diamond")
                .amount(1)
                .itemNbt(new byte[]{1})
                .inventorySlot(3)
                .pairId(pairId)
                .build();
    }
}
