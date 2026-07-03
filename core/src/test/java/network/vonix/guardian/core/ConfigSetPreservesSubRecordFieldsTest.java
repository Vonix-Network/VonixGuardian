/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.core;

import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.4 AA1 (P1 close-out) regression suite.
 *
 * <p>Prior to AA1, every cell's {@code /vg config set} switch built sub-records
 * via <em>backward-compat legacy shims</em> instead of the canonical constructors:
 * <ul>
 *   <li>{@code new GuardianConfig.LogFile(4-arg)} at line ~842 dropped
 *       {@code forceSyncOnFlush} (v1.3.1 X6, defaulted back to {@code true}).</li>
 *   <li>{@code new GuardianConfig.Actions(18-arg)} at lines ~851-864 dropped all
 *       13 W1 kill-switch fields plus W4's {@code mixinHotEvents} — silently
 *       defaulting them back to CP-parity defaults every time an operator ran
 *       {@code /vg config set actions.<anything> ...}.</li>
 * </ul>
 *
 * <p>Symptom: an operator who sets {@code actions.logWaterFlow=true} then later
 * flips {@code actions.logBlocks=false} loses the {@code logWaterFlow} setting
 * because the 18-arg shim resets it to {@code false} (CP default).
 *
 * <p>These tests pin the canonical contract from the core side. Every cell's
 * {@code withValue} / {@code withActions} helper MUST call the canonical LogFile
 * and Actions constructors — {@code LogFile(5-arg)} and {@code Actions(32-arg)}.
 * If any cell ever regresses to a shorter form, this suite catches it via a
 * pure round-trip mirror of the exact code the cell runs.
 *
 * @since 1.3.4
 */
class ConfigSetPreservesSubRecordFieldsTest {

    // ------------------------------------------------------- shared fixtures

    /** Non-default LogFile values so a legacy 4-arg shim would be caught. */
    private static GuardianConfig.LogFile hotLogFile() {
        return new GuardianConfig.LogFile(
            true,                        // enabled
            "logs/vonixguardian-hot",    // directory
            false,                       // gzipRotated (NON-default)
            7,                           // retentionDays (NON-default)
            false                        // forceSyncOnFlush = false (NON-default — the field the shim drops!)
        );
    }

    /**
     * Non-default Actions with EVERY W1 kill-switch and mixinHotEvents flipped
     * away from its CP-parity default. If any cell's withActions helper uses
     * the 18-arg shim, ALL of these will silently reset back to defaults.
     */
    private static GuardianConfig.Actions hotActions() {
        return new GuardianConfig.Actions(
            true, true, true, true, true, true, true, true, true, true, true,
            List.of(), List.of("minecraft:air"), List.of(),
            500L, 8192,
            List.of(), false,
            // ---- W5-07: 13 CP-parity toggles, ALL flipped from default ----
            false, // logNaturalBreaks         (default true)
            false, // logTreeGrowth            (default true)
            false, // logMushroomGrowth        (default true)
            false, // logVineGrowth            (default true)
            false, // logSculkSpread           (default true)
            false, // logPortals               (default true)
            true,  // logWaterFlow             (default false)
            true,  // logLavaFlow              (default false)
            false, // logFireExtinguish        (default true)
            false, // logCampfireStart         (default true)
            true,  // logHopperMetaFilter      (default false)
            false, // logDuplicateSuppression  (default true)
            true,  // logCancelledChat         (default false)
            // ---- W4 kill-switch, flipped from default ----
            false  // mixinHotEvents           (default true)
        );
    }

    private static GuardianConfig hotCfg() {
        return new GuardianConfig(
            new GuardianConfig.Database("sqlite", "test.db", null, null, null, null, GuardianConfig.Hikari.defaults()),
            new GuardianConfig.Queue(1000, 5_000L, 100),
            hotLogFile(),
            hotActions(),
            new GuardianConfig.Permissions(true, 3, java.util.Map.of()),
            new GuardianConfig.Lookup(7, 10_000, 100_000, 4),
            new GuardianConfig.Privacy(false, "some-16-char-salt-000000"),
            new GuardianConfig.Purge(86_400L, 3_600L, 0L, "03:30"),
            new GuardianConfig.Storage(true),
            new GuardianConfig.Rollback(16),
            "aqua",
            "en_us"
        );
    }

    /**
     * Byte-for-byte mirror of every cell's {@code withActions(c, a)} helper —
     * the one that wraps the Actions record back into a full GuardianConfig.
     * This alone does not exercise the sub-record ctor; use the cell mirrors
     * below (withLogFileEnabled / withActionsLogBlocks) to reproduce the exact
     * defect surface.
     */
    private static GuardianConfig withActions(GuardianConfig c, GuardianConfig.Actions a) {
        return new GuardianConfig(
            c.database(), c.queue(), c.logFile(), a,
            c.permissions(), c.lookup(), c.privacy(), c.purge(),
            c.storage(), c.rollback(), c.theme(), c.language()
        );
    }

    // -------------------------------------------------- mirrors of cell code

    /**
     * Byte-for-byte mirror of every cell's fixed {@code case "logFile.enabled"}
     * arm — the canonical 5-arg LogFile ctor threading {@code forceSyncOnFlush}
     * via {@code lf.forceSyncOnFlush()}. A regression to the 4-arg shim would
     * silently reset {@code forceSyncOnFlush} back to {@code true}.
     */
    private static GuardianConfig withLogFileEnabled(GuardianConfig c, boolean value) {
        GuardianConfig.LogFile lf = c.logFile();
        return new GuardianConfig(
            c.database(), c.queue(),
            new GuardianConfig.LogFile(
                value, lf.directory(), lf.gzipRotated(), lf.retentionDays(),
                lf.forceSyncOnFlush()
            ),
            c.actions(), c.permissions(), c.lookup(), c.privacy(), c.purge(),
            c.storage(), c.rollback(), c.theme(), c.language()
        );
    }

    /**
     * Byte-for-byte mirror of every cell's fixed {@code case "actions.logBlocks"}
     * arm — the canonical 32-arg Actions ctor threading all 13 W1 fields +
     * {@code mixinHotEvents}. A regression to the 18-arg shim would silently
     * reset every W1 field back to its CP-parity default.
     */
    private static GuardianConfig withActionsLogBlocks(GuardianConfig c, boolean value) {
        GuardianConfig.Actions a = c.actions();
        return withActions(c, new GuardianConfig.Actions(
            value, a.logContainers(), a.logItems(), a.logEntities(), a.logExplosions(),
            a.logChat(), a.logCommands(), a.logSessions(), a.logSigns(),
            a.logInteractions(), a.logWorldEvents(),
            a.worldBlacklist(), a.blockBlacklist(), a.sourceBlacklist(),
            a.entityBlockChangeCoalesceWindowMs(), a.entityBlockChangeMaxTracked(),
            a.entityChangeAllowlist(), a.entityChangeLogAllEntities(),
            a.logNaturalBreaks(), a.logTreeGrowth(), a.logMushroomGrowth(),
            a.logVineGrowth(), a.logSculkSpread(), a.logPortals(),
            a.logWaterFlow(), a.logLavaFlow(), a.logFireExtinguish(),
            a.logCampfireStart(), a.logHopperMetaFilter(),
            a.logDuplicateSuppression(), a.logCancelledChat(), a.mixinHotEvents()
        ));
    }

    // =========================================== LogFile.forceSyncOnFlush (X6)

    @Test
    @DisplayName("AA1 P1: /vg config set logFile.enabled true preserves forceSyncOnFlush=false")
    void configSetLogFileEnabledPreservesForceSyncOnFlush() {
        GuardianConfig c = hotCfg();
        assertThat(c.logFile().forceSyncOnFlush())
            .as("fixture: forceSyncOnFlush is non-default")
            .isFalse();

        GuardianConfig next = withLogFileEnabled(c, true);

        assertThat(next.logFile().enabled()).isTrue();
        assertThat(next.logFile().forceSyncOnFlush())
            .as("/vg config set logFile.enabled MUST NOT drop forceSyncOnFlush "
                + "(the LogFile(4-arg) shim would silently reset it to true)")
            .isFalse();
        // Other LogFile fields also preserved
        assertThat(next.logFile().directory()).isEqualTo("logs/vonixguardian-hot");
        assertThat(next.logFile().gzipRotated()).isFalse();
        assertThat(next.logFile().retentionDays()).isEqualTo(7);
    }

    // ================================================ Actions W1 kill-switches

    @Test
    @DisplayName("AA1 P1: /vg config set actions.logBlocks true preserves all 13 W1 kill-switches + mixinHotEvents")
    void configSetActionsLogBlocksPreservesAllW1Fields() {
        GuardianConfig c = hotCfg();
        GuardianConfig.Actions before = c.actions();

        GuardianConfig next = withActionsLogBlocks(c, true);
        GuardianConfig.Actions after = next.actions();

        assertThat(after.logBlocks()).isTrue();

        // All 13 W1 CP-parity toggles: must survive round-trip unchanged.
        assertThat(after.logNaturalBreaks()).isEqualTo(before.logNaturalBreaks()).isFalse();
        assertThat(after.logTreeGrowth()).isEqualTo(before.logTreeGrowth()).isFalse();
        assertThat(after.logMushroomGrowth()).isEqualTo(before.logMushroomGrowth()).isFalse();
        assertThat(after.logVineGrowth()).isEqualTo(before.logVineGrowth()).isFalse();
        assertThat(after.logSculkSpread()).isEqualTo(before.logSculkSpread()).isFalse();
        assertThat(after.logPortals()).isEqualTo(before.logPortals()).isFalse();
        assertThat(after.logWaterFlow()).isEqualTo(before.logWaterFlow()).isTrue();
        assertThat(after.logLavaFlow()).isEqualTo(before.logLavaFlow()).isTrue();
        assertThat(after.logFireExtinguish()).isEqualTo(before.logFireExtinguish()).isFalse();
        assertThat(after.logCampfireStart()).isEqualTo(before.logCampfireStart()).isFalse();
        assertThat(after.logHopperMetaFilter()).isEqualTo(before.logHopperMetaFilter()).isTrue();
        assertThat(after.logDuplicateSuppression()).isEqualTo(before.logDuplicateSuppression()).isFalse();
        assertThat(after.logCancelledChat()).isEqualTo(before.logCancelledChat()).isTrue();

        // W4 mixinHotEvents also preserved (was part of the 18-arg drop surface via delegation chain).
        assertThat(after.mixinHotEvents()).isEqualTo(before.mixinHotEvents()).isFalse();
    }

    // ================================ parameterised: every sub-record survives

    /**
     * Parameterised: for every sub-record field the AA1 sweep touched, verify
     * survival after an unrelated {@code /vg config set} case runs. Each row
     * flips a distinct field on the hot fixture then asserts every other field
     * in that sub-record kept its value.
     */
    static Stream<Arguments> subRecordFieldCases() {
        return Stream.of(
            Arguments.of("logFile.enabled → forceSyncOnFlush", "forceSyncOnFlush"),
            Arguments.of("logFile.enabled → directory",        "directory"),
            Arguments.of("logFile.enabled → gzipRotated",      "gzipRotated"),
            Arguments.of("logFile.enabled → retentionDays",    "retentionDays"),
            Arguments.of("actions.logBlocks → logNaturalBreaks",       "logNaturalBreaks"),
            Arguments.of("actions.logBlocks → logTreeGrowth",          "logTreeGrowth"),
            Arguments.of("actions.logBlocks → logMushroomGrowth",      "logMushroomGrowth"),
            Arguments.of("actions.logBlocks → logVineGrowth",          "logVineGrowth"),
            Arguments.of("actions.logBlocks → logSculkSpread",         "logSculkSpread"),
            Arguments.of("actions.logBlocks → logPortals",             "logPortals"),
            Arguments.of("actions.logBlocks → logWaterFlow",           "logWaterFlow"),
            Arguments.of("actions.logBlocks → logLavaFlow",            "logLavaFlow"),
            Arguments.of("actions.logBlocks → logFireExtinguish",      "logFireExtinguish"),
            Arguments.of("actions.logBlocks → logCampfireStart",       "logCampfireStart"),
            Arguments.of("actions.logBlocks → logHopperMetaFilter",    "logHopperMetaFilter"),
            Arguments.of("actions.logBlocks → logDuplicateSuppression","logDuplicateSuppression"),
            Arguments.of("actions.logBlocks → logCancelledChat",       "logCancelledChat"),
            Arguments.of("actions.logBlocks → mixinHotEvents",         "mixinHotEvents")
        );
    }

    @ParameterizedTest(name = "AA1 P1: {0} survives an unrelated set")
    @MethodSource("subRecordFieldCases")
    void everySubRecordFieldSurvivesUnrelatedSet(String label, String field) {
        GuardianConfig c = hotCfg();

        // Run BOTH mirrored cases — every field in this suite must survive both.
        GuardianConfig afterLogFileSet = withLogFileEnabled(c, true);
        GuardianConfig afterActionsSet = withActionsLogBlocks(c, true);

        switch (field) {
            case "forceSyncOnFlush" -> {
                assertThat(afterLogFileSet.logFile().forceSyncOnFlush()).isFalse();
                assertThat(afterActionsSet.logFile().forceSyncOnFlush()).isFalse();
            }
            case "directory" -> {
                assertThat(afterLogFileSet.logFile().directory()).isEqualTo("logs/vonixguardian-hot");
                assertThat(afterActionsSet.logFile().directory()).isEqualTo("logs/vonixguardian-hot");
            }
            case "gzipRotated" -> {
                assertThat(afterLogFileSet.logFile().gzipRotated()).isFalse();
                assertThat(afterActionsSet.logFile().gzipRotated()).isFalse();
            }
            case "retentionDays" -> {
                assertThat(afterLogFileSet.logFile().retentionDays()).isEqualTo(7);
                assertThat(afterActionsSet.logFile().retentionDays()).isEqualTo(7);
            }
            case "logNaturalBreaks" -> {
                assertThat(afterLogFileSet.actions().logNaturalBreaks()).isFalse();
                assertThat(afterActionsSet.actions().logNaturalBreaks()).isFalse();
            }
            case "logTreeGrowth" -> {
                assertThat(afterLogFileSet.actions().logTreeGrowth()).isFalse();
                assertThat(afterActionsSet.actions().logTreeGrowth()).isFalse();
            }
            case "logMushroomGrowth" -> {
                assertThat(afterLogFileSet.actions().logMushroomGrowth()).isFalse();
                assertThat(afterActionsSet.actions().logMushroomGrowth()).isFalse();
            }
            case "logVineGrowth" -> {
                assertThat(afterLogFileSet.actions().logVineGrowth()).isFalse();
                assertThat(afterActionsSet.actions().logVineGrowth()).isFalse();
            }
            case "logSculkSpread" -> {
                assertThat(afterLogFileSet.actions().logSculkSpread()).isFalse();
                assertThat(afterActionsSet.actions().logSculkSpread()).isFalse();
            }
            case "logPortals" -> {
                assertThat(afterLogFileSet.actions().logPortals()).isFalse();
                assertThat(afterActionsSet.actions().logPortals()).isFalse();
            }
            case "logWaterFlow" -> {
                assertThat(afterLogFileSet.actions().logWaterFlow()).isTrue();
                assertThat(afterActionsSet.actions().logWaterFlow()).isTrue();
            }
            case "logLavaFlow" -> {
                assertThat(afterLogFileSet.actions().logLavaFlow()).isTrue();
                assertThat(afterActionsSet.actions().logLavaFlow()).isTrue();
            }
            case "logFireExtinguish" -> {
                assertThat(afterLogFileSet.actions().logFireExtinguish()).isFalse();
                assertThat(afterActionsSet.actions().logFireExtinguish()).isFalse();
            }
            case "logCampfireStart" -> {
                assertThat(afterLogFileSet.actions().logCampfireStart()).isFalse();
                assertThat(afterActionsSet.actions().logCampfireStart()).isFalse();
            }
            case "logHopperMetaFilter" -> {
                assertThat(afterLogFileSet.actions().logHopperMetaFilter()).isTrue();
                assertThat(afterActionsSet.actions().logHopperMetaFilter()).isTrue();
            }
            case "logDuplicateSuppression" -> {
                assertThat(afterLogFileSet.actions().logDuplicateSuppression()).isFalse();
                assertThat(afterActionsSet.actions().logDuplicateSuppression()).isFalse();
            }
            case "logCancelledChat" -> {
                assertThat(afterLogFileSet.actions().logCancelledChat()).isTrue();
                assertThat(afterActionsSet.actions().logCancelledChat()).isTrue();
            }
            case "mixinHotEvents" -> {
                assertThat(afterLogFileSet.actions().mixinHotEvents()).isFalse();
                assertThat(afterActionsSet.actions().mixinHotEvents()).isFalse();
            }
            default -> throw new AssertionError("unknown field " + field);
        }
    }
}
