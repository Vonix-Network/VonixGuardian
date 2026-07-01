package network.vonix.guardian.core.blacklist;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistFileTest {

    @Test
    void parsesEachRuleKindIntoRightCollection() {
        BlacklistFile.Parsed p = BlacklistFile.parse(List.of(
                "user:Notch",
                "user_uuid:00000000-0000-0000-0000-000000000001",
                "command:tp",
                "block:minecraft:stone",
                "entity:minecraft:zombie",
                "minecraft:diamond_ore@Herobrine"
        ));
        assertTrue(p.userNames().contains("Notch"));
        assertTrue(p.userUuids().contains(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertTrue(p.commandPrefixes().contains("tp"));
        assertTrue(p.blockIds().contains("minecraft:stone"));
        assertTrue(p.entityIds().contains("minecraft:zombie"));
        assertTrue(p.composites().contains(
                new BlacklistFile.Composite("minecraft:diamond_ore", "Herobrine")));
        assertEquals(6, p.size());
    }

    @Test
    void ignoresBlanksAndComments() {
        BlacklistFile.Parsed p = BlacklistFile.parse(List.of(
                "",
                "   ",
                "# a comment",
                "  # indented comment",
                "user:Alice"
        ));
        assertEquals(1, p.size());
        assertTrue(p.userNames().contains("Alice"));
    }

    @Test
    void unknownKindSkipsRule() {
        BlacklistFile.Parsed p = BlacklistFile.parse(List.of(
                "wat:huh",
                "user:Bob"
        ));
        assertEquals(1, p.size());
        assertTrue(p.userNames().contains("Bob"));
    }

    @Test
    void malformedUuidSkipped() {
        BlacklistFile.Parsed p = BlacklistFile.parse(List.of("user_uuid:not-a-uuid"));
        assertEquals(0, p.size());
    }

    @Test
    void emptyValueSkipped() {
        BlacklistFile.Parsed p = BlacklistFile.parse(List.of("user:", "block:  "));
        assertEquals(0, p.size());
    }

    @Test
    void loadMissingFileReturnsEmpty() throws Exception {
        Path p = Path.of("/tmp/vg-nonexistent-" + System.nanoTime() + ".txt");
        BlacklistFile.Parsed parsed = BlacklistFile.load(p);
        assertEquals(0, parsed.size());
    }

    @Test
    void loadRealFile(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("blacklist.txt");
        Files.writeString(f, "user:Steve\n# ignored\nblock:minecraft:tnt\n");
        BlacklistFile.Parsed p = BlacklistFile.load(f);
        assertEquals(2, p.size());
        assertTrue(p.userNames().contains("Steve"));
        assertTrue(p.blockIds().contains("minecraft:tnt"));
    }

    @Test
    void compositeWithColonInId() {
        BlacklistFile.Parsed p = BlacklistFile.parse(List.of("minecraft:stone@Alice"));
        assertEquals(1, p.composites().size());
        BlacklistFile.Composite c = p.composites().iterator().next();
        assertEquals("minecraft:stone", c.id());
        assertEquals("Alice", c.userName());
    }
}
