package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X6 (P1-1 / P2-4): a pre-X6 config file with no {@code database.hikari}
 * section must load without throwing; the section must be backfilled to
 * {@link GuardianConfig.Hikari#defaults()} so downstream DAO ctors never NPE
 * on {@code cfg.hikari().maxPoolSize()}.
 */
class ConfigLoaderHikariBackfillTest {

    @Test
    void pre_x6_config_gets_hikari_backfill(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
        // Minimal pre-X6 config: no database.hikari section. Storage present so we
        // don't trip other backfills; language + theme present; database.type=sqlite.
        String json = """
            {
              "database": { "type": "sqlite", "file": "vg.db" },
              "queue": { "maxSize": 50000, "flushIntervalMs": 5000, "batchSize": 1000 },
              "logFile": { "enabled": true, "directory": "logs/vg", "gzipRotated": true, "retentionDays": 30 },
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
        assertThat(cfg.database().hikari()).isNotNull();
        assertThat(cfg.database().hikari().maxPoolSize()).isEqualTo(10);
        assertThat(cfg.database().hikari().connectionTimeoutMs()).isEqualTo(30_000L);
    }
}
