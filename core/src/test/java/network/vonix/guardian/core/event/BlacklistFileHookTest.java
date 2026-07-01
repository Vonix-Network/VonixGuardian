package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.blacklist.BlacklistFile;
import network.vonix.guardian.core.blacklist.BlacklistMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistFileHookTest {

    private static Action a(ActionType t, String actor, String targetId) {
        return new ActionBuilder()
                .type(t)
                .actorName(actor)
                .worldId("minecraft:overworld")
                .targetId(targetId)
                .build();
    }

    private static BlacklistFileHook hook(String... lines) {
        return new BlacklistFileHook(new BlacklistMatcher(BlacklistFile.parse(List.of(lines))));
    }

    @Test
    void deniesWhenMatcherMatches() {
        BlacklistFileHook h = hook("user:Herobrine");
        assertEquals(EventHook.Decision.DENY,
                h.test(a(ActionType.BLOCK_BREAK, "Herobrine", "minecraft:stone")));
    }

    @Test
    void passesWhenMatcherDoesNotMatch() {
        BlacklistFileHook h = hook("user:Herobrine");
        assertEquals(EventHook.Decision.PASS,
                h.test(a(ActionType.BLOCK_BREAK, "Steve", "minecraft:stone")));
    }

    @Test
    void neverAccepts() {
        // The hook uses DENY / PASS only; ACCEPT is reserved for allow-list hooks.
        BlacklistFileHook h = hook("block:minecraft:stone");
        for (Action act : List.of(
                a(ActionType.BLOCK_PLACE, "Al", "minecraft:stone"),
                a(ActionType.CHAT,        "Al", "hi"),
                a(ActionType.ENTITY_KILL, "Al", "minecraft:pig"))) {
            assertNotEquals(EventHook.Decision.ACCEPT, h.test(act));
        }
    }

    @Test
    void exposesMatcher() {
        BlacklistFileHook h = hook("user:X");
        assertNotNull(h.matcher());
        assertEquals(1, h.matcher().size());
    }

    @Test
    void nullMatcherRejected() {
        assertThrows(NullPointerException.class, () -> new BlacklistFileHook(null));
    }
}
