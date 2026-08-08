package network.vonix.guardian.core;

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
        GuardianConfig defaults = GuardianConfig.defaults();
        GuardianConfig config = new GuardianConfig(
                new GuardianConfig.Database("sqlite", tmp.resolve("boundary.db").toString(),
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

        Guardian guardian = Guardian.boot(config, tmp, NOOP, ZERO_OP, SYNC, threads);
        try {
            UUID actor = UUID.randomUUID();
            Action direct = new Action(-1L, 123L, ActionType.COMMAND, actor, "Alice",
                    "minecraft:overworld", 1, 2, 3, "/give Alice minecraft:diamond 64",
                    "meta", 2, false, "source", null, null, null,
                    null, null, null, null, null);

            assertThat(guardian.submitAccepted(direct)).isTrue();
            List<Action> rows = List.of();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (rows.isEmpty() && System.nanoTime() < deadline) {
                rows = guardian.dao().query(QueryFilter.empty(), 0, 10);
                if (rows.isEmpty()) Thread.sleep(10L);
            }

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).targetId()).isEqualTo("/give");
            assertThat(rows.get(0).targetMeta()).isEqualTo("meta");
            assertThat(rows.get(0).x()).isEqualTo(1);
        } finally {
            guardian.close();
        }
    }
}
