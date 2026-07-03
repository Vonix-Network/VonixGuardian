package network.vonix.guardian.core.diagnostics;

import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.perms.OpLevelFallback;
import network.vonix.guardian.core.rollback.WorldMutator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.0 W4 — verifies the new "Mixin hot events" section of {@code /vg status}.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@code mixinHotEvents=true} → section reports "enabled  yes".</li>
 *   <li>{@code mixinHotEvents=false} → section reports "enabled  no (kill-switch engaged)".</li>
 *   <li>All 8 mixin-sourced ActionType rows (BURN, SPREAD, IGNITE, FADE, FORM,
 *       LEAVES_DECAY, DISPENSE, ENTITY_CHANGE_BLOCK) always render, even when the
 *       rate is 0/s.</li>
 *   <li>An {@code allocRate} line is always rendered.</li>
 *   <li>The Guardian.submit() fast-path drops actions with {@code "#fire"},
 *       {@code "#natural"}, or {@code "#dispenser"} sourceTags when the flag is off
 *       (spot-check via the gated counter, exposed in the queue section).</li>
 * </ol>
 */
class GuardianStatusMixinSectionTest {

    private static final Executor SYNC = Runnable::run;
    private static final ThreadFactory DAEMONS = r -> {
        Thread t = new Thread(r, "vg-status-mixin-test");
        t.setDaemon(true);
        return t;
    };
    private static final WorldMutator NOOP_MUTATOR = new WorldMutator() {
        @Override public void setBlock(String worldId, int x, int y, int z, String targetId, String targetMeta) {}
        @Override public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String targetMeta) {}
        @Override public void removeFromContainer(String worldId, int x, int y, int z, String itemId, int amount) {}
        @Override public void respawnEntity(String worldId, int x, int y, int z, String entityType, String targetMeta) {}
    };
    private static final OpLevelFallback ZERO_OP = uuid -> 0;

    private static GuardianConfig cfgWith(Path tmp, boolean mixinHotEvents) {
        var d = GuardianConfig.defaults();
        var a = d.actions();
        var flippedActions = new GuardianConfig.Actions(
            a.logBlocks(), a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(),
            a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(), a.logInteractions(),
            a.logWorldEvents(),
            a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(),
            a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(),
            a.entityChangeAllowlist(), a.entityChangeLogAllEntities(),
            a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(), a.logVineGrowth(),
            a.logSculkSpread(), a.logPortals(), a.logWaterFlow(), a.logLavaFlow(),
            a.logFireExtinguish(), a.logCampfireStart(), a.logHopperMetaFilter(),
            a.logDuplicateSuppression(), a.logCancelledChat(),
            mixinHotEvents
        );
        return new GuardianConfig(
            new GuardianConfig.Database("sqlite", tmp.resolve("status-mixin.db").toString(), null, null, null),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            new GuardianConfig.LogFile(false, "logs", true, 30),
            flippedActions,
            d.permissions(),
            d.lookup(),
            d.privacy(),
            d.purge(),
            d.theme(),
            d.language()
        );
    }

    @Test
    void mixinSectionShowsAllEightTypesAndEnabledWhenFlagOn(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(cfgWith(tmp, true), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            String report = String.join("\n", GuardianStatus.render(g));
            assertThat(report).contains("§ Mixin hot events");
            assertThat(report).contains("enabled    yes");
            assertThat(report).contains("allocRate");
            for (String type : List.of(
                    "BURN", "SPREAD", "IGNITE", "FADE", "FORM",
                    "LEAVES_DECAY", "DISPENSE", "ENTITY_CHANGE_BLOCK")) {
                assertThat(report)
                    .as("mixin section must include a row for %s", type)
                    .contains(type);
            }
        } finally {
            g.close();
        }
    }

    @Test
    void mixinSectionSignalsKillSwitchWhenFlagOff(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(cfgWith(tmp, false), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            String report = String.join("\n", GuardianStatus.render(g));
            assertThat(report).contains("§ Mixin hot events");
            assertThat(report).contains("no (kill-switch engaged)");
        } finally {
            g.close();
        }
    }

    @Test
    void guardianSubmitFastPathGatesMixinTaggedActionsWhenFlagOff(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(cfgWith(tmp, false), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            long gatedBefore = g.gated();
            long submittedBefore = g.submitted();

            // Mixin-sourced BURN → must be gated (dropped before EventGate).
            g.submitBurn(null, "#natural", "minecraft:overworld", 0, 64, 0,
                "minecraft:oak_log", "#fire:spread");
            // Mixin-sourced SPREAD → gated.
            g.submitSpread(null, "#natural", "minecraft:overworld", 1, 64, 0,
                "minecraft:grass_block", "#natural:spread");
            // Mixin-sourced DISPENSE → gated.
            g.submitDispense(null, "#natural", "minecraft:overworld", 2, 64, 0,
                "minecraft:water_bucket", "#dispenser:fluid");
            // Non-mixin action (regular player BURN with a normal sourceTag) → passes through.
            g.submitBurn(null, "#natural", "minecraft:overworld", 3, 64, 0,
                "minecraft:oak_log", "player:break");

            long gatedAfter = g.gated();
            long submittedAfter = g.submitted();

            assertThat(gatedAfter - gatedBefore)
                .as("all three mixin-tagged submits should be gated")
                .isGreaterThanOrEqualTo(3);
            assertThat(submittedAfter - submittedBefore)
                .as("the non-mixin submit should have gone through")
                .isGreaterThanOrEqualTo(1);
        } finally {
            g.close();
        }
    }

    @Test
    void guardianSubmitFastPathAllowsMixinTaggedActionsWhenFlagOn(@TempDir Path tmp) throws Exception {
        Guardian g = Guardian.boot(cfgWith(tmp, true), tmp, NOOP_MUTATOR, ZERO_OP, SYNC, DAEMONS);
        try {
            long submittedBefore = g.submitted();

            // With the kill-switch OFF (flag ON), the mixin-tagged submit reaches the queue.
            g.submitBurn(null, "#natural", "minecraft:overworld", 0, 64, 0,
                "minecraft:oak_log", "#fire:spread");

            long submittedAfter = g.submitted();
            assertThat(submittedAfter - submittedBefore).isGreaterThanOrEqualTo(1);
        } finally {
            g.close();
        }
    }
}
