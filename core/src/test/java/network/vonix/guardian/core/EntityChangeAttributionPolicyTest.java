package network.vonix.guardian.core;

import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for v1.2.5 modded entity attribution policy. */
class EntityChangeAttributionPolicyTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-entity-policy-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {}
        @Override public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {}
        @Override public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {}
        @Override public void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {}
    };

    @Test
    void playerCreditedModdedEntityChangeStillRequiresSourceEntityAllowlist(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(config(tmp, List.of(), false), tmp, NOOP_MUTATOR, uuid -> 0, SYNC, DAEMONS);
        try {
            UUID owner = UUID.randomUUID();
            g.submitEntityChangeBlock(owner, "Alice", "minecraft:overworld", 1, 64, 1,
                    "minecraft:grass_block", "minecraft:air", "#mob:isleofberk:night_fury");

            assertThat(g.submitted()).isZero();
            assertThat(g.gated()).isEqualTo(1L);
        } finally {
            g.close();
        }
    }

    @Test
    void configuredModdedEntitySourceCanBeRecordedWithPlayerAttribution(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(config(tmp, List.of("isleofberk:night_fury"), false), tmp, NOOP_MUTATOR, uuid -> 0, SYNC, DAEMONS);
        try {
            UUID owner = UUID.randomUUID();
            g.submitEntityChangeBlock(owner, "Alice", "minecraft:overworld", 1, 64, 1,
                    "minecraft:grass_block", "minecraft:air", "#mob:isleofberk:night_fury");

            assertThat(g.submitted()).isEqualTo(1L);
            assertThat(g.gated()).isZero();
        } finally {
            g.close();
        }
    }

    private static GuardianConfig config(Path tmp, List<String> allowlist, boolean logAll) {
        GuardianConfig base = GuardianConfig.defaults();
        GuardianConfig.Actions a = base.actions();
        GuardianConfig.Actions actions = new GuardianConfig.Actions(
                a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(),
                a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(), a.logWorldEvents(),
                a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(),
                -1L, 0,
                allowlist, logAll,
                a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(),
                a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(),
                a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(),
                a.logDuplicateSuppression(), a.logCancelledChat());
        return new GuardianConfig(
                new GuardianConfig.Database("sqlite", tmp.resolve("entity-policy.db").toString(), null, null, null),
                new GuardianConfig.Queue(100, 5_000L, 10),
                new GuardianConfig.LogFile(false, "logs", true, 30),
                actions,
                base.permissions(),
                base.lookup(),
                base.privacy(),
                new GuardianConfig.Purge(86_400L, 2_592_000L, 0L, "03:30"),
                base.theme(),
                base.language());
    }
}
