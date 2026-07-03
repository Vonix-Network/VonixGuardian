package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import network.vonix.guardian.core.config.PerWorldConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerWorldEventHookTest {

    private static GuardianConfig.Actions root() {
        return new GuardianConfig.Actions(
            true, true, true, true, true, true, true, true, true, true, true,
            List.of(), List.of(), List.of(),
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

    private static Action action(ActionType type, String world, String target, String sourceTag) {
        return new Action(
            -1L, 1_700_000_000_000L, type,
            UUID.randomUUID(), "Notch",
            world, 0, 64, 0,
            target, null, 1, false, sourceTag);
    }

    @Test
    void deny_when_world_override_disables_blocks(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__the_nether.json"),
            "{ \"logBlocks\": false }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        PerWorldEventHook hook = new PerWorldEventHook(store, root());

        Action inNether = action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:netherrack", null);
        assertThat(hook.test(inNether)).isEqualTo(EventHook.Decision.DENY);

        // Non-block category is not affected by logBlocks
        Action chatInNether = action(ActionType.CHAT, "minecraft:the_nether", "hello", null);
        assertThat(hook.test(chatInNether)).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void pass_when_world_has_no_override(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__the_nether.json"),
            "{ \"logBlocks\": false }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        PerWorldEventHook hook = new PerWorldEventHook(store, root());

        Action inOverworld = action(ActionType.BLOCK_BREAK, "minecraft:overworld", "minecraft:stone", null);
        assertThat(hook.test(inOverworld)).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void deny_when_block_in_override_blacklist(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__the_nether.json"),
            "{ \"blockBlacklist\": [\"minecraft:netherrack\"] }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        PerWorldEventHook hook = new PerWorldEventHook(store, root());

        Action a = action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:netherrack", null);
        assertThat(hook.test(a)).isEqualTo(EventHook.Decision.DENY);

        Action b = action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:stone", null);
        assertThat(hook.test(b)).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void deny_when_source_tag_in_override_blacklist(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__the_nether.json"),
            "{ \"sourceBlacklist\": [\"explosion:ghast\"] }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        PerWorldEventHook hook = new PerWorldEventHook(store, root());

        Action a = action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:stone", "explosion:ghast");
        assertThat(hook.test(a)).isEqualTo(EventHook.Decision.DENY);

        Action b = action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:stone", "explosion:tnt");
        assertThat(hook.test(b)).isEqualTo(EventHook.Decision.PASS);
    }

    @Test
    void deny_when_world_in_override_worldblacklist(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("minecraft__the_nether.json"),
            "{ \"worldBlacklist\": [\"minecraft:the_nether\"] }");
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        store.reload(tmp);
        PerWorldEventHook hook = new PerWorldEventHook(store, root());

        Action a = action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:stone", null);
        assertThat(hook.test(a)).isEqualTo(EventHook.Decision.DENY);
    }

    @Test
    void empty_store_always_passes() {
        PerWorldConfigStore store = new PerWorldConfigStore(root());
        // no reload → empty
        PerWorldEventHook hook = new PerWorldEventHook(store, root());
        Action a = action(ActionType.BLOCK_BREAK, "minecraft:overworld", "minecraft:stone", null);
        assertThat(hook.test(a)).isEqualTo(EventHook.Decision.PASS);
    }
}
