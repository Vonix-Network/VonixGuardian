package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.config.GuardianConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventGateTest {

    // ---- helpers --------------------------------------------------------

    private static GuardianConfig.Actions cfg(boolean all,
                                              List<String> worlds,
                                              List<String> blocks,
                                              List<String> sources) {
        return new GuardianConfig.Actions(
                all, all, all, all, all, all, all, all, all,
                all, all,
                worlds, blocks, sources,
                60_000L, 512, List.of(), false);
    }

    private static GuardianConfig.Actions allOn() {
        return cfg(true, List.of(), List.of(), List.of());
    }

    private static GuardianConfig.Actions allOff() {
        return cfg(false, List.of(), List.of(), List.of());
    }

    private static Action action(ActionType type, String world, String target, String sourceTag) {
        return new Action(
                -1L, 1_700_000_000_000L, type,
                UUID.randomUUID(), "Notch",
                world, 1, 64, 2,
                target, null, 1, false, sourceTag);
    }

    // ---- constructor ----------------------------------------------------

    @Test
    void rejects_null_config() {
        assertThatThrownBy(() -> new EventGate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void tolerates_null_blacklist_lists() {
        GuardianConfig.Actions c = new GuardianConfig.Actions(
                true, true, true, true, true, true, true, true, true, true, true,
                null, null, null,
                60_000L, 512, List.of(), false);
        EventGate g = new EventGate(c);
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "minecraft:overworld", "minecraft:stone", null)))
                .isTrue();
    }

    @Test
    void null_action_is_dropped() {
        assertThat(new EventGate(allOn()).shouldLog(null)).isFalse();
    }

    // ---- per-type toggles ----------------------------------------------

    static Stream<Arguments> toggleCases() {
        // (type, world/target/source picked so only the toggle decides)
        return Stream.of(
                Arguments.of(ActionType.BLOCK_PLACE,        "logBlocks"),
                Arguments.of(ActionType.BLOCK_BREAK,        "logBlocks"),
                Arguments.of(ActionType.CONTAINER_DEPOSIT,  "logContainers"),
                Arguments.of(ActionType.CONTAINER_WITHDRAW, "logContainers"),
                Arguments.of(ActionType.ITEM_DROP,          "logItems"),
                Arguments.of(ActionType.ITEM_PICKUP,        "logItems"),
                Arguments.of(ActionType.ENTITY_KILL,        "logEntities"),
                Arguments.of(ActionType.EXPLOSION,          "logExplosions"),
                Arguments.of(ActionType.CHAT,               "logChat"),
                Arguments.of(ActionType.COMMAND,            "logCommands"),
                Arguments.of(ActionType.SIGN,               "logSigns"),
                Arguments.of(ActionType.SESSION_JOIN,       "logSessions"),
                Arguments.of(ActionType.SESSION_LEAVE,      "logSessions"),
                Arguments.of(ActionType.USERNAME_CHANGE,    "logSessions")
        );
    }

    @ParameterizedTest(name = "{0} passes when {1}=true")
    @MethodSource("toggleCases")
    void toggle_on_lets_action_through(ActionType type, String toggle) {
        Action a = action(type, "minecraft:overworld", "minecraft:stone", null);
        assertThat(new EventGate(allOn()).shouldLog(a))
                .as("toggle %s on for %s", toggle, type)
                .isTrue();
    }

    @ParameterizedTest(name = "{0} dropped when {1}=false")
    @MethodSource("toggleCases")
    void toggle_off_drops_action(ActionType type, String toggle) {
        Action a = action(type, "minecraft:overworld", "minecraft:stone", null);
        assertThat(new EventGate(allOff()).shouldLog(a))
                .as("toggle %s off for %s", toggle, type)
                .isFalse();
    }

    @Test
    void per_family_isolation_blocks_off_only_drops_blocks() {
        GuardianConfig.Actions c = new GuardianConfig.Actions(
                false, true, true, true, true, true, true, true, true, true, true,
                List.of(), List.of(), List.of(),
                60_000L, 512, List.of(), false);
        EventGate g = new EventGate(c);
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "w", "minecraft:stone", null))).isFalse();
        assertThat(g.shouldLog(action(ActionType.BLOCK_PLACE, "w", "minecraft:stone", null))).isFalse();
        assertThat(g.shouldLog(action(ActionType.CONTAINER_DEPOSIT, "w", "minecraft:stone", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.ITEM_DROP, "w", "minecraft:stone", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.ENTITY_KILL, "w", "minecraft:zombie", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.EXPLOSION, "w", "x", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.CHAT, "w", "hi", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.COMMAND, "w", "/x", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.SIGN, "w", "hi", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.SESSION_JOIN, "w", "ip", null))).isTrue();
    }

    // ---- world blacklist ------------------------------------------------

    @Test
    void world_blacklist_drops_matching_world() {
        GuardianConfig.Actions c = cfg(true,
                List.of("minecraft:the_nether"), List.of(), List.of());
        EventGate g = new EventGate(c);
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "minecraft:the_nether", "minecraft:netherrack", null)))
                .isFalse();
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "minecraft:overworld", "minecraft:stone", null)))
                .isTrue();
    }

    @Test
    void world_blacklist_applies_to_all_types() {
        GuardianConfig.Actions c = cfg(true, List.of("w"), List.of(), List.of());
        EventGate g = new EventGate(c);
        for (ActionType t : ActionType.values()) {
            assertThat(g.shouldLog(action(t, "w", "minecraft:stone", null)))
                    .as("world blacklist must drop %s", t).isFalse();
        }
    }

    // ---- block blacklist ------------------------------------------------

    @Test
    void block_blacklist_drops_matching_block_actions_only() {
        GuardianConfig.Actions c = cfg(true,
                List.of(), List.of("minecraft:dirt"), List.of());
        EventGate g = new EventGate(c);
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "w", "minecraft:dirt", null))).isFalse();
        assertThat(g.shouldLog(action(ActionType.BLOCK_PLACE, "w", "minecraft:dirt", null))).isFalse();
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "w", "minecraft:stone", null))).isTrue();
    }

    @Test
    void block_blacklist_does_not_affect_non_block_actions_even_with_matching_target() {
        GuardianConfig.Actions c = cfg(true,
                List.of(), List.of("minecraft:dirt"), List.of());
        EventGate g = new EventGate(c);
        // a non-block action whose targetId happens to equal a blacklisted block id must pass
        assertThat(g.shouldLog(action(ActionType.ITEM_DROP, "w", "minecraft:dirt", null))).isTrue();
        assertThat(g.shouldLog(action(ActionType.CONTAINER_DEPOSIT, "w", "minecraft:dirt", null))).isTrue();
    }

    // ---- source blacklist ----------------------------------------------

    @Test
    void source_blacklist_drops_matching_source_tag() {
        GuardianConfig.Actions c = cfg(true,
                List.of(), List.of(), List.of("explosion:tnt"));
        EventGate g = new EventGate(c);
        assertThat(g.shouldLog(action(ActionType.EXPLOSION, "w", "x", "explosion:tnt"))).isFalse();
        assertThat(g.shouldLog(action(ActionType.EXPLOSION, "w", "x", "explosion:creeper"))).isTrue();
    }

    @Test
    void null_source_never_matches_source_blacklist() {
        GuardianConfig.Actions c = cfg(true,
                List.of(), List.of(), List.of("explosion:tnt"));
        EventGate g = new EventGate(c);
        assertThat(g.shouldLog(action(ActionType.BLOCK_BREAK, "w", "minecraft:stone", null))).isTrue();
    }
}
