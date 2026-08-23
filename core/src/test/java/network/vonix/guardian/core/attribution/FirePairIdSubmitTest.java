package network.vonix.guardian.core.attribution;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.ConfigLoader;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.event.EventHook;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer → Action: an allowlisted entity break and the paired ignite share
 * a durable pairId on the submitted Action even though EventSubmitter still
 * uses the historical 8-arg signatures.
 */
class FirePairIdSubmitTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-pairid-submit-test");
        t.setDaemon(true);
        return t;
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;
    private static final WorldMutator NOOP = new WorldMutator() {
        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) { return true; }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m) { return true; }
        @Override public boolean tryRemoveFromContainer(String w, int x, int y, int z, String t, int a) { return true; }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String t, String m) { return true; }
    };

    @Test
    void entityChangeAndPairedIgniteShareDurablePairId(@TempDir Path tmp) throws Exception {
        GuardianConfig cfg = new GuardianConfig(
            new GuardianConfig.Database("sqlite", tmp.resolve("test.db").toString(), null, null, null, null, GuardianConfig.Hikari.defaults()),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30, true),
            new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of("minecraft:air"), List.of(),
                500L, 8192,
                List.of(), false,
                true, true, true, true, true, true, false, false, true, true, false, true, false, true),
            new GuardianConfig.Permissions(true, 3, java.util.Map.of()),
            new GuardianConfig.Lookup(10, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(86_400L, 3_600L, 0L, "03:30"),
            new GuardianConfig.Storage(false),
            GuardianConfig.Rollback.defaults(),
            "aqua",
            "en_us"
        );
        Path cfgPath = tmp.resolve("config.json");
        ConfigLoader.save(cfgPath, cfg);
        Guardian g = Guardian.boot(ConfigLoader.load(cfgPath), tmp, NOOP, ZERO_OP, SYNC, DAEMONS);
        try {
            List<Action> captured = new ArrayList<>();
            g.gate().addHook(action -> {
                captured.add(action);
                return EventHook.Decision.DENY;
            });

            UUID actor = UUID.randomUUID();
            g.fireCauserMemory().record("minecraft:overworld", 10, 64, 20,
                    FireCauserMemory.CauserRecord.allowlisted(actor, "Toothless",
                            "isleofberk:nightfury", "#entity", 42L, System.currentTimeMillis()));

            g.submitEntityChangeBlock(actor, "Toothless", "minecraft:overworld",
                    10, 64, 20, "minecraft:oak_log", "minecraft:air", "#entity");

            UniversalAttribution.FireCauser v =
                    UniversalAttribution.resolveFireCauser(g.fireCauserMemory(),
                            "minecraft:overworld", 11, 64, 20);
            assertThat(v.verdict).isEqualTo(UniversalAttribution.FireVerdict.PAIR);
            g.submitIgnite(v.actorUuid, v.actorName, "minecraft:overworld",
                    11, 64, 20, "minecraft:fire", "entity:#entity");

            assertThat(captured).hasSize(2);
            assertThat(captured.get(0).type()).isEqualTo(ActionType.ENTITY_CHANGE_BLOCK);
            assertThat(captured.get(1).type()).isEqualTo(ActionType.IGNITE);
            assertThat(captured.get(0).pairId()).isEqualTo(42L);
            assertThat(captured.get(1).pairId()).isEqualTo(42L);
        } finally {
            g.close();
        }
    }
}
