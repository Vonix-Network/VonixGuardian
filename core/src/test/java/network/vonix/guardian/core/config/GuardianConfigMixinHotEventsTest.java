package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.3.0 W4 — regression tests for the new {@code actions.mixinHotEvents} config field.
 *
 * <p>Covers:
 * <ol>
 *   <li>{@link GuardianConfig#defaults()} sets {@code mixinHotEvents=true}.</li>
 *   <li>Backfill from a pre-W4 (pre-W5-07) config on disk: field missing → true after load.</li>
 *   <li>Explicit operator {@code false} on a modern config is preserved (never overwritten).</li>
 *   <li>Validation accepts both true/false without additional constraints.</li>
 * </ol>
 */
class GuardianConfigMixinHotEventsTest {

    @Test
    void defaults_mixinHotEvents_is_true() {
        assertThat(GuardianConfig.defaults().actions().mixinHotEvents()).isTrue();
    }

    @Test
    void validate_accepts_true_and_false() {
        // true (default)
        GuardianConfig.defaults().validate();
        // false — kill-switch engaged should also be a legal config
        GuardianConfig c = GuardianConfig.defaults();
        var a = c.actions();
        var flipped = new GuardianConfig.Actions(
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
            false // mixinHotEvents flipped
        );
        var c2 = new GuardianConfig(
            c.database(), c.queue(), c.logFile(), flipped, c.permissions(),
            c.lookup(), c.privacy(), c.purge(), c.theme(), c.language()
        );
        c2.validate();
        assertThat(c2.actions().mixinHotEvents()).isFalse();
    }

    /**
     * Pre-W4 configs on disk have NO {@code mixinHotEvents} key. Gson deserialises the
     * missing field to {@code false}. The forward-compat migrator must detect this and
     * backfill to {@code true} (the safe default matching pre-W4 always-on behaviour).
     *
     * <p>The migrator's heuristic is: if the config also has all W5-07 toggles false
     * (which universally means "pre-W5-07 config, and therefore also pre-W4"), then
     * backfill {@code mixinHotEvents=true}.
     */
    @Test
    void migrate_preW4_config_backfills_mixinHotEvents_true(@TempDir Path tmp) throws Exception {
        // Minimal pre-W4 config: everything at the older defaults, no W5-07 toggles,
        // no mixinHotEvents. This matches a config emitted by v1.2.0-era ConfigLoader.
        Path cfgPath = tmp.resolve("config.json");
        String json = "{\n" +
            "  \"database\": {\"type\": \"sqlite\", \"file\": \"vg.db\"},\n" +
            "  \"queue\":    {\"maxSize\": 1000, \"flushIntervalMs\": 5000, \"batchSize\": 100},\n" +
            "  \"logFile\":  {\"enabled\": false, \"directory\": \"logs\", \"gzipRotated\": true, \"retentionDays\": 30},\n" +
            "  \"actions\":  {\n" +
            "    \"logBlocks\": true, \"logContainers\": true, \"logItems\": true,\n" +
            "    \"logEntities\": true, \"logExplosions\": true, \"logChat\": true,\n" +
            "    \"logCommands\": true, \"logSessions\": true, \"logSigns\": true,\n" +
            "    \"logInteractions\": true, \"logWorldEvents\": true,\n" +
            "    \"worldBlacklist\": [], \"blockBlacklist\": [\"minecraft:air\"], \"sourceBlacklist\": [],\n" +
            "    \"entityBlockChangeCoalesceWindowMs\": 500, \"entityBlockChangeMaxTracked\": 8192,\n" +
            "    \"entityChangeAllowlist\": [], \"entityChangeLogAllEntities\": false\n" +
            "  },\n" +
            "  \"permissions\": {\"useLuckPerms\": true, \"defaultOpLevel\": 3},\n" +
            "  \"lookup\":  {\"defaultPageSize\": 7, \"maxRadius\": 10000, \"maxResultRows\": 100000, \"maxConcurrent\": 4},\n" +
            "  \"privacy\": {\"hashIps\": false, \"salt\": \"some-16-char-salt-000000\"},\n" +
            "  \"purge\":   {\"minAgeSecondsConsole\": 86400, \"minAgeSecondsInGame\": 3600, \"autoPurgeSeconds\": 0, \"autoPurgeTime\": \"03:30\"},\n" +
            "  \"theme\":   \"aqua\",\n" +
            "  \"language\":\"en_us\"\n" +
            "}\n";
        Files.writeString(cfgPath, json, StandardOpenOption.CREATE_NEW);

        GuardianConfig loaded = ConfigLoader.load(cfgPath);
        assertThat(loaded.actions().mixinHotEvents())
            .as("pre-W4 config with no mixinHotEvents key must backfill to true")
            .isTrue();
    }

    /**
     * On a modern (post-W4) config where the operator explicitly writes
     * {@code mixinHotEvents=false} to engage the kill-switch, the migrator must
     * NOT overwrite the operator's choice. Reproduce a config where at least one
     * W5-07 toggle is true (so the "all W5-07 false" heuristic doesn't fire) and
     * mixinHotEvents=false — the loader must preserve false.
     */
    @Test
    void modern_config_with_explicit_false_is_preserved(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        String json = "{\n" +
            "  \"database\": {\"type\": \"sqlite\", \"file\": \"vg.db\"},\n" +
            "  \"queue\":    {\"maxSize\": 1000, \"flushIntervalMs\": 5000, \"batchSize\": 100},\n" +
            "  \"logFile\":  {\"enabled\": false, \"directory\": \"logs\", \"gzipRotated\": true, \"retentionDays\": 30},\n" +
            "  \"actions\":  {\n" +
            "    \"logBlocks\": true, \"logContainers\": true, \"logItems\": true,\n" +
            "    \"logEntities\": true, \"logExplosions\": true, \"logChat\": true,\n" +
            "    \"logCommands\": true, \"logSessions\": true, \"logSigns\": true,\n" +
            "    \"logInteractions\": true, \"logWorldEvents\": true,\n" +
            "    \"worldBlacklist\": [], \"blockBlacklist\": [\"minecraft:air\"], \"sourceBlacklist\": [],\n" +
            "    \"entityBlockChangeCoalesceWindowMs\": 500, \"entityBlockChangeMaxTracked\": 8192,\n" +
            "    \"entityChangeAllowlist\": [], \"entityChangeLogAllEntities\": false,\n" +
            // At least one W5-07 toggle true → post-W5-07 config → migrator won't touch anything.
            "    \"logNaturalBreaks\": true, \"logTreeGrowth\": true, \"logMushroomGrowth\": true,\n" +
            "    \"logVineGrowth\": true, \"logSculkSpread\": true, \"logPortals\": true,\n" +
            "    \"logWaterFlow\": false, \"logLavaFlow\": false, \"logFireExtinguish\": true,\n" +
            "    \"logCampfireStart\": true, \"logHopperMetaFilter\": false,\n" +
            "    \"logDuplicateSuppression\": true, \"logCancelledChat\": false,\n" +
            "    \"mixinHotEvents\": false\n" +
            "  },\n" +
            "  \"permissions\": {\"useLuckPerms\": true, \"defaultOpLevel\": 3},\n" +
            "  \"lookup\":  {\"defaultPageSize\": 7, \"maxRadius\": 10000, \"maxResultRows\": 100000, \"maxConcurrent\": 4},\n" +
            "  \"privacy\": {\"hashIps\": false, \"salt\": \"some-16-char-salt-000000\"},\n" +
            "  \"purge\":   {\"minAgeSecondsConsole\": 86400, \"minAgeSecondsInGame\": 3600, \"autoPurgeSeconds\": 0, \"autoPurgeTime\": \"03:30\"},\n" +
            "  \"theme\":   \"aqua\",\n" +
            "  \"language\":\"en_us\"\n" +
            "}\n";
        Files.writeString(cfgPath, json, StandardOpenOption.CREATE_NEW);

        GuardianConfig loaded = ConfigLoader.load(cfgPath);
        assertThat(loaded.actions().mixinHotEvents())
            .as("modern config with explicit false must be preserved")
            .isFalse();
    }

    @Test
    void save_roundtrip_preserves_flag(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Start from defaults, flip mixinHotEvents to false, save + reload.
        GuardianConfig defaults = GuardianConfig.defaults();
        var a = defaults.actions();
        var flipped = new GuardianConfig.Actions(
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
            false
        );
        GuardianConfig toSave = new GuardianConfig(
            defaults.database(), defaults.queue(), defaults.logFile(), flipped,
            defaults.permissions(), defaults.lookup(), defaults.privacy(),
            defaults.purge(), defaults.theme(), defaults.language()
        );
        ConfigLoader.save(cfgPath, toSave);

        GuardianConfig loaded = ConfigLoader.load(cfgPath);
        assertThat(loaded.actions().mixinHotEvents()).isFalse();
    }

    @Test
    void invalid_theme_still_errors_regardless_of_mixin_flag() {
        // Sanity: adding mixinHotEvents didn't accidentally break other validate() paths.
        GuardianConfig c = GuardianConfig.defaults();
        var broken = new GuardianConfig(
            c.database(), c.queue(), c.logFile(), c.actions(), c.permissions(),
            c.lookup(), c.privacy(), c.purge(), "not-a-theme", c.language()
        );
        assertThatThrownBy(broken::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("theme");
    }
}
