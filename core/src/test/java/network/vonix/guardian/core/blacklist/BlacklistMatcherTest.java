package network.vonix.guardian.core.blacklist;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistMatcherTest {

    private static Action a(ActionType type, String actor, UUID uuid, String targetId) {
        return new ActionBuilder()
                .type(type)
                .actorName(actor)
                .actorUuid(uuid)
                .worldId("minecraft:overworld")
                .targetId(targetId)
                .build();
    }

    private static BlacklistMatcher matcher(String... lines) {
        return new BlacklistMatcher(BlacklistFile.parse(List.of(lines)));
    }

    @Test
    void userNameRuleMatchesCaseInsensitively() {
        BlacklistMatcher m = matcher("user:Notch");
        assertTrue(m.matches(a(ActionType.BLOCK_PLACE, "notch", null, "minecraft:stone")));
        assertTrue(m.matches(a(ActionType.BLOCK_PLACE, "NOTCH", null, "minecraft:stone")));
        assertFalse(m.matches(a(ActionType.BLOCK_PLACE, "Steve", null, "minecraft:stone")));
    }

    @Test
    void userUuidRuleMatches() {
        UUID u = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        BlacklistMatcher m = matcher("user_uuid:" + u);
        assertTrue(m.matches(a(ActionType.BLOCK_BREAK, "Steve", u, "minecraft:dirt")));
        assertFalse(m.matches(a(ActionType.BLOCK_BREAK, "Steve",
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), "minecraft:dirt")));
    }

    @Test
    void commandRuleMatchesCommandActionsOnly() {
        BlacklistMatcher m = matcher("command:tp");
        assertTrue(m.matches(a(ActionType.COMMAND, "Alice", null, "/tp Bob")));
        assertTrue(m.matches(a(ActionType.COMMAND, "Alice", null, "/TP Bob")));
        assertTrue(m.matches(a(ActionType.COMMAND, "Alice", null, "tp Bob"))); // no slash tolerated
        assertFalse(m.matches(a(ActionType.COMMAND, "Alice", null, "/tpa Bob"))); // different cmd
        assertFalse(m.matches(a(ActionType.CHAT, "Alice", null, "/tp Bob"))); // non-command action
    }

    @Test
    void blockRuleMatchesBlockActionsOnly() {
        BlacklistMatcher m = matcher("block:minecraft:stone");
        assertTrue(m.matches(a(ActionType.BLOCK_PLACE, "Al", null, "minecraft:stone")));
        assertTrue(m.matches(a(ActionType.BLOCK_BREAK, "Al", null, "minecraft:stone")));
        assertFalse(m.matches(a(ActionType.BLOCK_PLACE, "Al", null, "minecraft:dirt")));
        assertFalse(m.matches(a(ActionType.ENTITY_KILL, "Al", null, "minecraft:stone")));
    }

    @Test
    void entityRuleMatchesEntityKillAndSpawn() {
        BlacklistMatcher m = matcher("entity:minecraft:zombie");
        assertTrue(m.matches(a(ActionType.ENTITY_KILL, "Al", null, "minecraft:zombie")));
        assertTrue(m.matches(a(ActionType.ENTITY_SPAWN, "Al", null, "minecraft:zombie")));
        assertFalse(m.matches(a(ActionType.ENTITY_KILL, "Al", null, "minecraft:pig")));
    }

    @Test
    void compositeRequiresBothIdAndUser() {
        BlacklistMatcher m = matcher("minecraft:diamond_ore@Herobrine");
        assertTrue(m.matches(a(ActionType.BLOCK_BREAK, "Herobrine", null, "minecraft:diamond_ore")));
        assertTrue(m.matches(a(ActionType.BLOCK_BREAK, "HEROBRINE", null, "minecraft:diamond_ore")));
        assertFalse(m.matches(a(ActionType.BLOCK_BREAK, "Steve", null, "minecraft:diamond_ore")));
        assertFalse(m.matches(a(ActionType.BLOCK_BREAK, "Herobrine", null, "minecraft:stone")));
    }

    @Test
    void emptyMatcherMatchesNothing() {
        BlacklistMatcher m = new BlacklistMatcher(BlacklistFile.Parsed.empty());
        assertTrue(m.isEmpty());
        assertFalse(m.matches(a(ActionType.BLOCK_PLACE, "x", null, "y")));
    }

    @Test
    void sizeTracksAllRuleKinds() {
        BlacklistMatcher m = matcher("user:A", "block:x", "entity:y");
        assertEquals(3, m.size());
    }
}
