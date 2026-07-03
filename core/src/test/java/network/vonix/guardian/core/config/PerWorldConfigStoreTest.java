package network.vonix.guardian.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PerWorldConfigStoreTest {

    private static GuardianConfig.Actions root() {
        return new GuardianConfig.Actions(
            true, true, true, true, true, true, true, true, true, true, true,
            List.of("minecraft:disabled_dim"),
            List.of("minecraft:air"),
            List.of(),
            500L, 8192,
            List.of(), false
        ,
        true,
        true,
        true,
        true,
        true,
        true,
        false,
        false,
        true,
        true,
        false,
        true,
        false,
        true);
    }

    @Test
    void loads_and_merges_two_override_files(@TempDir Path tmp) throws Exception {
        // world_the_nether: turn off block logging + add a block to blacklist
        Files.writeString(tmp.resolve("minecraft__the_nether.json"),
            "{\n" +
            "  \"logBlocks\": false,\n" +
            "  \"blockBlacklist\": [\"minecraft:netherrack\", \"minecraft:soul_sand\"]\n" +
            "}");
        // world_overworld: turn off chat only
        Files.writeString(tmp.resolve("minecraft__overworld.json"),
            "{ \"logChat\": false }");

        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);

        assertThat(store.overriddenWorlds())
                .containsExactlyInAnyOrder("minecraft:the_nether", "minecraft:overworld");

        GuardianConfig.Actions nether = store.overrideFor("minecraft:the_nether");
        assertThat(nether).isNotNull();
        assertThat(nether.logBlocks()).isFalse();
        assertThat(nether.logChat()).isTrue(); // inherited from root
        assertThat(nether.blockBlacklist())
                .containsExactly("minecraft:netherrack", "minecraft:soul_sand");
        assertThat(nether.worldBlacklist()).containsExactly("minecraft:disabled_dim"); // inherited

        GuardianConfig.Actions over = store.overrideFor("minecraft:overworld");
        assertThat(over).isNotNull();
        assertThat(over.logChat()).isFalse();
        assertThat(over.logBlocks()).isTrue();
        assertThat(over.blockBlacklist()).containsExactly("minecraft:air"); // inherited

        assertThat(store.overrideFor("minecraft:the_end")).isNull(); // no override → null

        assertThat(store.loadedFilenames())
                .containsExactly("minecraft__overworld.json", "minecraft__the_nether.json");
    }

    @Test
    void missing_dir_yields_empty_store() {
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(Path.of("/nonexistent-vg-worlds-dir-xyz"));
        assertThat(store.overriddenWorlds()).isEmpty();
        assertThat(store.overrideFor("minecraft:overworld")).isNull();
    }

    @Test
    void null_dir_yields_empty_store() {
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(null);
        assertThat(store.overriddenWorlds()).isEmpty();
    }

    @Test
    void malformed_file_is_skipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__broken.json"), "{ not json");
        Files.writeString(tmp.resolve("minecraft__good.json"), "{ \"logItems\": false }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        // broken skipped, good loaded
        assertThat(store.overriddenWorlds()).containsExactly("minecraft:good");
        assertThat(store.overrideFor("minecraft:good").logItems()).isFalse();
    }

    @Test
    void non_object_file_is_skipped(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__arr.json"), "[1,2,3]");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        assertThat(store.overriddenWorlds()).isEmpty();
    }

    @Test
    void reload_replaces_previous_snapshot(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__a.json"), "{ \"logBlocks\": false }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        assertThat(store.overriddenWorlds()).containsExactly("minecraft:a");

        Files.delete(tmp.resolve("minecraft__a.json"));
        Files.writeString(tmp.resolve("minecraft__b.json"), "{ \"logChat\": false }");
        store.reload(tmp);
        assertThat(store.overriddenWorlds()).containsExactly("minecraft:b");
    }
}
