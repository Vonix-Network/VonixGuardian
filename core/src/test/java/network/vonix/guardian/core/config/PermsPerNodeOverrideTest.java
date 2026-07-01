package network.vonix.guardian.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import network.vonix.guardian.core.perms.PermissionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3-B8: verify the {@code permissions.perNodeOpLevels} config override map is honoured through
 * the {@link ConfigLoader} + {@link GuardianConfig#validate()} pipeline.
 */
class PermsPerNodeOverrideTest {

    @Test
    void perNodeOverrideMapReplacesDefaultForNamedNode(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
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
                "entityChangeAllowlist": [], "entityChangeLogAllEntities": false
              },
              "permissions": {
                "useLuckPerms": false,
                "defaultOpLevel": 2,
                "perNodeOpLevels": {
                  "vonixguardian.lookup": 4,
                  "vonixguardian.purge":  1
                }
              },
              "lookup": { "defaultPageSize": 7, "maxRadius": 10000, "maxResultRows": 100000, "maxConcurrent": 4 },
              "privacy": { "hashIps": false, "salt": "vonix-guardian-default-salt-CHANGE-ME" },
              "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000, "autoPurgeSeconds": 0, "autoPurgeTime": "03:30" },
              "theme": "aqua"
            }
            """;
        Files.writeString(cfgPath, json, StandardCharsets.UTF_8);

        GuardianConfig cfg = ConfigLoader.load(cfgPath);
        Map<String, Integer> overrides = cfg.permissions().perNodeOpLevelsOrEmpty();

        assertThat(overrides).containsEntry(PermissionNode.LOOKUP.node(), 4);
        assertThat(overrides).containsEntry(PermissionNode.PURGE.node(), 1);
        assertThat(cfg.permissions().defaultOpLevel()).isEqualTo(2);
    }

    @Test
    void preB8ConfigMissingPerNodeMapBackfilledToEmpty(@TempDir Path tmp) throws Exception {
        // Simulate a pre-W3-B8 config where the permissions block lacks perNodeOpLevels entirely.
        Path cfgPath = tmp.resolve("config.json");
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
                "entityChangeAllowlist": [], "entityChangeLogAllEntities": false
              },
              "permissions": { "useLuckPerms": true, "defaultOpLevel": 3 },
              "lookup": { "defaultPageSize": 7, "maxRadius": 10000, "maxResultRows": 100000, "maxConcurrent": 4 },
              "privacy": { "hashIps": false, "salt": "vonix-guardian-default-salt-CHANGE-ME" },
              "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000, "autoPurgeSeconds": 0, "autoPurgeTime": "03:30" },
              "theme": "aqua"
            }
            """;
        Files.writeString(cfgPath, json, StandardCharsets.UTF_8);

        GuardianConfig cfg = ConfigLoader.load(cfgPath);
        // Backfilled to an empty (non-null) map by migrateForwardCompat.
        assertThat(cfg.permissions().perNodeOpLevelsOrEmpty()).isEmpty();
    }

    @Test
    void unknownNodeKey_isTolerated_asWarnOnly(@TempDir Path tmp) throws Exception {
        Path cfgPath = tmp.resolve("config.json");
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
                "entityChangeAllowlist": [], "entityChangeLogAllEntities": false
              },
              "permissions": {
                "useLuckPerms": false, "defaultOpLevel": 2,
                "perNodeOpLevels": { "vonixguardian.doesnotexist": 3 }
              },
              "lookup": { "defaultPageSize": 7, "maxRadius": 10000, "maxResultRows": 100000, "maxConcurrent": 4 },
              "privacy": { "hashIps": false, "salt": "vonix-guardian-default-salt-CHANGE-ME" },
              "purge": { "minAgeSecondsConsole": 86400, "minAgeSecondsInGame": 2592000, "autoPurgeSeconds": 0, "autoPurgeTime": "03:30" },
              "theme": "aqua"
            }
            """;
        Files.writeString(cfgPath, json, StandardCharsets.UTF_8);

        // Must not throw — unknown keys warn+skip only.
        GuardianConfig cfg = ConfigLoader.load(cfgPath);
        assertThat(cfg.permissions().perNodeOpLevelsOrEmpty()).containsKey("vonixguardian.doesnotexist");
    }

    @Test
    void outOfRangeOverrideValue_isValidationError() {
        Map<String, Integer> bad = Map.of(PermissionNode.LOOKUP.node(), 9);
        GuardianConfig d = GuardianConfig.defaults();
        GuardianConfig cfg = new GuardianConfig(
                d.database(), d.queue(), d.logFile(), d.actions(),
                new GuardianConfig.Permissions(false, 2, bad),
                d.lookup(), d.privacy(), d.purge(), d.theme());
        assertThatThrownBy(cfg::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("perNodeOpLevels");
    }
}
