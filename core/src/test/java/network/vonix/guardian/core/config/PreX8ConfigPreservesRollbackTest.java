package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.5 BB1 (P1): a pre-X8 on-disk config with an operator-customised
 * {@code rollback.explosionSupplementalReach} must survive
 * {@link ConfigLoader#migrateForwardCompat}, even when other backfill branches
 * fire on the same load (e.g. the W5-07 CP-parity rewrite that returns from
 * the terminal ctor site at ConfigLoader.java:273).
 *
 * <p>Before BB1, all 6 {@code new GuardianConfig(...)} sites inside
 * {@code migrateForwardCompat} routed through the pre-X8 11-arg back-compat
 * ctor, which silently reset {@code rollback} to {@link GuardianConfig.Rollback#defaults()}.
 * An operator who had set {@code explosionSupplementalReach=64} for a
 * mega-explosive modpack would have their value clobbered back to 16 on any
 * upgrade-path config load.
 *
 * <p>This test uses the W5-07 rewrite branch specifically because it is the
 * terminal ctor site (line 273) and the one guaranteed to fire when the config
 * has all 13 CP-parity toggles false — a hallmark of any pre-W5-07 config.
 */
class PreX8ConfigPreservesRollbackTest {

    @Test
    void pre_w5_07_rewrite_preserves_custom_rollback_reach(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Pre-W5-07 config: all 13 CP-parity toggles false, triggering the
        // ConfigLoader:273 return-branch rewrite. Rollback.explosionSupplementalReach
        // is set to a non-default 64 by the operator. Storage + Hikari + language
        // present so we isolate to the terminal rewrite path.
        String json = """
            {
              "database": {
                "type": "sqlite", "file": "vg.db",
                "hikari": { "maxPoolSize": 10, "connectionTimeoutMs": 30000, "idleTimeoutMs": 600000, "maxLifetimeMs": 1800000, "minimumIdle": 2, "keepaliveTimeMs": 0, "leakDetectionThresholdMs": 0 }
              },
              "queue": { "maxSize": 50000, "flushIntervalMs": 5000, "batchSize": 1000 },
              "logFile": { "enabled": true, "directory": "logs/vg", "gzipRotated": true, "retentionDays": 30, "forceSyncOnFlush": false },
              "actions": {
                "logBlocks": true, "logContainers": true, "logItems": true, "logEntities": true,
                "logExplosions": true, "logChat": true, "logCommands": true, "logSessions": true,
                "logSigns": true, "logInteractions": true, "logWorldEvents": true,
                "worldBlacklist": [], "blockBlacklist": ["minecraft:air"], "sourceBlacklist": [],
                "entityBlockChangeCoalesceWindowMs": 500, "entityBlockChangeMaxTracked": 8192,
                "entityChangeAllowlist": [], "entityChangeLogAllEntities": false,
                "logNaturalBreaks": false, "logTreeGrowth": false, "logMushroomGrowth": false,
                "logVineGrowth": false, "logSculkSpread": false, "logPortals": false,
                "logWaterFlow": false, "logLavaFlow": false, "logFireExtinguish": false,
                "logCampfireStart": false, "logHopperMetaFilter": false,
                "logDuplicateSuppression": false, "logCancelledChat": false,
                "mixinHotEvents": false
              },
              "permissions": { "useLuckPerms": true, "defaultOpLevel": 3, "perNodeOpLevels": {} },
              "lookup": { "defaultPageSize": 7, "maxRadius": 10000, "maxResultRows": 100000, "maxConcurrent": 4 },
              "privacy": { "hashIps": false, "salt": "vonix-guardian-default-salt-CHANGE-ME" },
              "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000, "autoPurgeSeconds": 0, "autoPurgeTime": "03:30" },
              "storage": { "persistNbt": false },
              "rollback": { "explosionSupplementalReach": 64 },
              "theme": "aqua",
              "language": "en_us"
            }
            """;
        Files.writeString(cfgPath, json);

        GuardianConfig cfg = ConfigLoader.load(cfgPath);

        // The W5-07 rewrite branch MUST have fired (all 13 toggles were false),
        // so the terminal ctor at ConfigLoader:273 was exercised — sanity-check
        // that the CP-parity backfill happened.
        assertThat(cfg.actions().logNaturalBreaks())
            .as("W5-07 CP-parity backfill should have set logNaturalBreaks=true")
            .isTrue();

        // The whole point of BB1: rollback.explosionSupplementalReach must
        // survive the rewrite. Before BB1 this was silently reset to 16.
        assertThat(cfg.rollback()).isNotNull();
        assertThat(cfg.rollback().explosionSupplementalReach())
            .as("operator-set rollback.explosionSupplementalReach must survive migrateForwardCompat")
            .isEqualTo(64);
    }

    @Test
    void pre_w3_b4_purge_backfill_preserves_custom_rollback_reach(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Pre-W3-B4 config: purge.autoPurgeTime null triggers the ConfigLoader:137
        // backfill branch. All CP-parity toggles true so the terminal rewrite path
        // does NOT fire — this test isolates to the first ctor site.
        String json = """
            {
              "database": {
                "type": "sqlite", "file": "vg.db",
                "hikari": { "maxPoolSize": 10, "connectionTimeoutMs": 30000, "idleTimeoutMs": 600000, "maxLifetimeMs": 1800000, "minimumIdle": 2, "keepaliveTimeMs": 0, "leakDetectionThresholdMs": 0 }
              },
              "queue": { "maxSize": 50000, "flushIntervalMs": 5000, "batchSize": 1000 },
              "logFile": { "enabled": true, "directory": "logs/vg", "gzipRotated": true, "retentionDays": 30, "forceSyncOnFlush": false },
              "actions": {
                "logBlocks": true, "logContainers": true, "logItems": true, "logEntities": true,
                "logExplosions": true, "logChat": true, "logCommands": true, "logSessions": true,
                "logSigns": true, "logInteractions": true, "logWorldEvents": true,
                "worldBlacklist": [], "blockBlacklist": ["minecraft:air"], "sourceBlacklist": [],
                "entityBlockChangeCoalesceWindowMs": 500, "entityBlockChangeMaxTracked": 8192,
                "entityChangeAllowlist": [], "entityChangeLogAllEntities": false,
                "logNaturalBreaks": true, "logTreeGrowth": true, "logMushroomGrowth": true,
                "logVineGrowth": true, "logSculkSpread": true, "logPortals": true,
                "logWaterFlow": false, "logLavaFlow": false, "logFireExtinguish": true,
                "logCampfireStart": true, "logHopperMetaFilter": false,
                "logDuplicateSuppression": true, "logCancelledChat": false,
                "mixinHotEvents": true
              },
              "permissions": { "useLuckPerms": true, "defaultOpLevel": 3, "perNodeOpLevels": {} },
              "lookup": { "defaultPageSize": 7, "maxRadius": 10000, "maxResultRows": 100000, "maxConcurrent": 4 },
              "privacy": { "hashIps": false, "salt": "vonix-guardian-default-salt-CHANGE-ME" },
              "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000, "autoPurgeSeconds": 0 },
              "storage": { "persistNbt": false },
              "rollback": { "explosionSupplementalReach": 32 },
              "theme": "aqua",
              "language": "en_us"
            }
            """;
        Files.writeString(cfgPath, json);

        GuardianConfig cfg = ConfigLoader.load(cfgPath);

        assertThat(cfg.purge().autoPurgeTime())
            .as("pre-W3-B4 purge.autoPurgeTime backfill should have set 03:30")
            .isEqualTo("03:30");
        assertThat(cfg.rollback().explosionSupplementalReach())
            .as("operator-set rollback.explosionSupplementalReach must survive purge backfill")
            .isEqualTo(32);
    }
}
