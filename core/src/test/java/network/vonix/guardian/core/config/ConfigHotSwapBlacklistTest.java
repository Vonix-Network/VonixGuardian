package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Setting the coalescer window used to call the deprecated {@code Actions}
 * constructor, which substitutes empty lists for the item and entity
 * blacklists and then gets saved. These assertions fail on that constructor:
 * the lists come back empty and the saved file loses them.
 */
class ConfigHotSwapBlacklistTest {

    @Test
    void coalesceWindowKeepsBlacklistsInMemoryAndOnDisk(@TempDir Path tmp) throws Exception {
        GuardianConfig cfg = withLists();

        GuardianConfig next = ConfigHotSwap.apply(cfg, "actions.entityBlockChangeCoalesceWindowMs", "2500");

        assertThat(next.actions().entityBlockChangeCoalesceWindowMs()).isEqualTo(2500L);
        assertListsSurvive(cfg, next);
        assertThat(next.actions().entityChangeAllowlist()).containsExactly("isleofberk:nightmare");
        assertThat(next.actions().logWaterFlow()).isEqualTo(cfg.actions().logWaterFlow());
        assertThat(next.actions().mixinHotEvents()).isEqualTo(cfg.actions().mixinHotEvents());
        assertThat(next.actions().blockBlacklist()).containsExactlyElementsOf(cfg.actions().blockBlacklist());

        Path file = tmp.resolve("config.json");
        ConfigLoader.save(file, next);
        GuardianConfig loaded = ConfigLoader.load(file);
        assertThat(loaded.actions().entityBlockChangeCoalesceWindowMs()).isEqualTo(2500L);
        assertThat(loaded.actions().itemBlacklist()).containsExactly("minecraft:diamond", "minecraft:tnt");
        assertThat(loaded.actions().entityBlacklist()).containsExactly("minecraft:creeper");
        assertThat(loaded.actions().entityChangeAllowlist()).containsExactly("isleofberk:nightmare");
    }

    @Test
    void everyHotSwapKeyKeepsTheOtherBlacklist() {
        GuardianConfig cfg = withLists();
        String[] keys = {
            "theme", "logFile.enabled",
            "lookup.defaultPageSize", "lookup.maxRadius", "lookup.maxResultRows",
            "privacy.hashIps",
            "purge.minAgeSecondsConsole", "purge.minAgeSecondsInGame",
            "purge.autoPurgeSeconds", "purge.autoPurgeTime",
            "actions.logBlocks", "actions.logContainers", "actions.logItems", "actions.logEntities",
            "actions.logExplosions", "actions.logChat", "actions.logCommands", "actions.logSessions",
            "actions.logSigns", "actions.logInteractions", "actions.logWorldEvents",
            "actions.entityBlockChangeCoalesceWindowMs", "actions.entityBlockChangeMaxTracked",
            "actions.entityChangeLogAllEntities", "actions.mixinHotEvents",
            "actions.itemBlacklist", "actions.entityBlacklist",
            "storage.persistNbt", "rollback.explosionSupplementalReach", "language"
        };
        String[] values = {
            "paper", "false",
            "9", "20", "50",
            "false",
            "86400", "2592000",
            "0", "04:15",
            "false", "false", "false", "false",
            "false", "false", "false", "false",
            "false", "false", "false",
            "2500", "100",
            "true", "false",
            "minecraft:stick", "minecraft:zombie",
            "true", "8", "en_us"
        };
        assertThat(keys).hasSameSizeAs(values);
        for (int i = 0; i < keys.length; i++) {
            GuardianConfig next = ConfigHotSwap.apply(cfg, keys[i], values[i]);
            assertThat(next).as(keys[i]).isNotNull();
            if (!"actions.itemBlacklist".equals(keys[i])) {
                assertThat(next.actions().itemBlacklist()).as(keys[i]).containsExactly("minecraft:diamond", "minecraft:tnt");
            } else {
                assertThat(next.actions().itemBlacklist()).containsExactly("minecraft:stick");
            }
            if (!"actions.entityBlacklist".equals(keys[i])) {
                assertThat(next.actions().entityBlacklist()).as(keys[i]).containsExactly("minecraft:creeper");
            } else {
                assertThat(next.actions().entityBlacklist()).containsExactly("minecraft:zombie");
            }
            assertThat(ConfigHotSwap.read(next, keys[i])).as(keys[i]).isNotNull();
        }
    }

    @Test
    void unknownKeyIsRejectedAndBadNumbersDoNotBuildAConfig() {
        GuardianConfig cfg = withLists();
        assertThat(ConfigHotSwap.apply(cfg, "actions.notAKey", "1")).isNull();
        assertThatThrownBy(() -> ConfigHotSwap.apply(cfg, "actions.entityBlockChangeCoalesceWindowMs", "soon"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("integer");
        assertThat(cfg.actions().itemBlacklist()).containsExactly("minecraft:diamond", "minecraft:tnt");
    }

    private static void assertListsSurvive(GuardianConfig before, GuardianConfig after) {
        assertThat(after.actions().itemBlacklist()).containsExactlyElementsOf(before.actions().itemBlacklist());
        assertThat(after.actions().entityBlacklist()).containsExactlyElementsOf(before.actions().entityBlacklist());
        assertThat(after.actions().worldBlacklist()).containsExactlyElementsOf(before.actions().worldBlacklist());
        assertThat(after.actions().sourceBlacklist()).containsExactlyElementsOf(before.actions().sourceBlacklist());
    }

    private static GuardianConfig withLists() {
        GuardianConfig base = GuardianConfig.defaults();
        GuardianConfig.Actions actions = base.actions()
            .withItemBlacklist(List.of("minecraft:diamond", "minecraft:tnt"))
            .withEntityBlacklist(List.of("minecraft:creeper"))
            .withEntityChangeAllowlist(List.of("isleofberk:nightmare"));
        return new GuardianConfig(
            base.database(), base.queue(), base.logFile(), actions,
            base.permissions(), base.lookup(), base.privacy(), base.purge(),
            base.storage(), base.rollback(), base.theme(), base.language());
    }
}
