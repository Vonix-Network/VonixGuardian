package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.3.1 X6 (P3-7): {@link PerWorldConfigStore#reload} caches per-file mtimes
 * and skips the readString + Gson.fromJson chain when nothing has changed.
 */
class PerWorldConfigMtimeCacheTest {

    private static GuardianConfig.Actions root() {
        return GuardianConfig.defaults().actions();
    }

    @Test
    void unchanged_files_reuse_prior_snapshot_across_reload(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("minecraft__overworld.json");
        Files.writeString(file, "{ \"logChat\": false }");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));

        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        GuardianConfig.Actions firstSnap = store.overrideFor("minecraft:overworld");
        assertThat(firstSnap).isNotNull();
        assertThat(firstSnap.logChat()).isFalse();

        // Reload without touching the file — cache hit path must reuse the same
        // instance (or at least an equivalent one from the previous snapshot).
        store.reload(tmp);
        GuardianConfig.Actions secondSnap = store.overrideFor("minecraft:overworld");
        assertThat(secondSnap).isSameAs(firstSnap);
    }

    @Test
    void mtime_change_reparses_file(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("minecraft__overworld.json");
        Files.writeString(file, "{ \"logChat\": false }");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));

        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        GuardianConfig.Actions first = store.overrideFor("minecraft:overworld");
        assertThat(first.logChat()).isFalse();

        // Rewrite the file with a different content AND a newer mtime.
        Files.writeString(file, "{ \"logChat\": true, \"logCommands\": false }");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-02-01T00:00:00Z")));
        store.reload(tmp);
        GuardianConfig.Actions second = store.overrideFor("minecraft:overworld");
        assertThat(second).isNotSameAs(first);
        assertThat(second.logChat()).isTrue();
        assertThat(second.logCommands()).isFalse();
    }

    @Test
    void updateRoot_invalidates_mtime_cache(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("minecraft__overworld.json");
        Files.writeString(file, "{ \"logChat\": false }");
        FileTime stableMtime = FileTime.from(Instant.parse("2026-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, stableMtime);

        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        GuardianConfig.Actions first = store.overrideFor("minecraft:overworld");

        // Root changes → merged snapshot must be recomputed even at the same mtime.
        GuardianConfig.Actions newRoot = new GuardianConfig.Actions(
            false /* logBlocks flipped */, root().logContainers(), root().logItems(),
            root().logEntities(), root().logExplosions(), root().logChat(),
            root().logCommands(), root().logSessions(), root().logSigns(),
            root().logInteractions(), root().logWorldEvents(),
            root().worldBlacklist(), root().blockBlacklist(), root().sourceBlacklist(),
            root().entityBlockChangeCoalesceWindowMs(), root().entityBlockChangeMaxTracked(),
            root().entityChangeAllowlist(), root().entityChangeLogAllEntities(),
            root().logNaturalBreaks(), root().logTreeGrowth(), root().logMushroomGrowth(),
            root().logVineGrowth(), root().logSculkSpread(), root().logPortals(),
            root().logWaterFlow(), root().logLavaFlow(), root().logFireExtinguish(),
            root().logCampfireStart(), root().logHopperMetaFilter(),
            root().logDuplicateSuppression(), root().logCancelledChat(),
            root().mixinHotEvents()
        );
        store.updateRoot(newRoot);
        Files.setLastModifiedTime(file, stableMtime); // mtime unchanged
        store.reload(tmp);
        GuardianConfig.Actions second = store.overrideFor("minecraft:overworld");
        assertThat(second).isNotSameAs(first);
        assertThat(second.logBlocks()).isFalse();
    }
}
