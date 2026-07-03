package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.5 BB1 (P1): a fully-pre-X8 on-disk config with NO {@code rollback}
 * section at all must migrate cleanly to {@link GuardianConfig.Rollback#defaults()}
 * with no NPE across every {@link ConfigLoader#migrateForwardCompat} branch.
 *
 * <p>The compact canonical ctor at {@code GuardianConfig.java:57} catches
 * {@code rollback == null} and substitutes defaults, but before BB1 the six
 * pre-X8 back-compat ctor sites inside {@code migrateForwardCompat} routed
 * through the {@code GuardianConfig.java:66} 11-arg shim which always used
 * {@code Rollback.defaults()} regardless of the input {@code work.rollback()}.
 * After BB1 the sites explicitly thread {@code work.rollback() == null ? defaults() : work.rollback()}
 * — so both the null-input and non-null-input paths must exit clean.
 *
 * <p>This test drives the null-input case: the on-disk JSON has no
 * {@code rollback} key, Gson deserialises it to null, the compact ctor at
 * GuardianConfig:57 backfills to defaults before migrateForwardCompat runs,
 * and every subsequent ctor threading {@code work.rollback()} sees a non-null
 * defaults() instance and preserves it through the migration chain.
 */
class FullyPreX8ConfigMigratesRollbackDefaultsTest {

    @Test
    void pre_x8_config_no_rollback_section_uses_defaults_after_migration(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Genuinely-pre-X8 config: no rollback section, plus pre-W3-B4
        // (purge.autoPurgeTime absent) so the ConfigLoader:137 ctor also fires,
        // exercising more than one back-compat branch on a null-rollback input.
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
              "theme": "aqua",
              "language": "en_us"
            }
            """;
        Files.writeString(cfgPath, json);

        // Must not throw — the migration chain must handle the whole trip
        // with rollback backfilled by the canonical compact ctor.
        GuardianConfig cfg = ConfigLoader.load(cfgPath);

        // The pre-W3-B4 purge backfill fired (autoPurgeTime absent), so
        // the ConfigLoader:137 site was exercised on the null-rollback input.
        assertThat(cfg.purge().autoPurgeTime()).isEqualTo("03:30");

        // Rollback survives as defaults() — 16 blocks, the vanilla-TNT reach.
        assertThat(cfg.rollback()).isNotNull();
        assertThat(cfg.rollback().explosionSupplementalReach())
            .as("fully-pre-X8 config must resolve to Rollback.defaults() (16)")
            .isEqualTo(GuardianConfig.Rollback.defaults().explosionSupplementalReach());
        assertThat(cfg.rollback().explosionSupplementalReach()).isEqualTo(16);
    }

    @Test
    void pre_x8_config_no_backfill_needed_stays_at_defaults(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Post-migration-clean config that just happens to omit the rollback
        // section (as any config exported before v1.3.1 X8 would). No backfill
        // branches should fire — only the compact ctor at GuardianConfig:57 kicks in.
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
              "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000, "autoPurgeSeconds": 0, "autoPurgeTime": "03:30" },
              "storage": { "persistNbt": false },
              "theme": "aqua",
              "language": "en_us"
            }
            """;
        Files.writeString(cfgPath, json);

        GuardianConfig cfg = ConfigLoader.load(cfgPath);

        assertThat(cfg.rollback()).isNotNull();
        assertThat(cfg.rollback().explosionSupplementalReach()).isEqualTo(16);
    }
}
