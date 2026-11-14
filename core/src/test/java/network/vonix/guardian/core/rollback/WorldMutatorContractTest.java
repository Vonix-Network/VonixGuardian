package network.vonix.guardian.core.rollback;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link WorldMutator} contract, exercised without a Minecraft runtime.
 *
 * <p>Two properties are load-bearing and neither was true before this test
 * existed:
 *
 * <ol>
 *   <li><strong>A compatibility default must not mutate.</strong> Every checked
 *       {@code try*} default used to call the legacy {@code void} method and
 *       then return {@code false}. An implementation written against the old
 *       ABI therefore changed the world <em>and</em> reported failure. The
 *       engine reads {@code false} as "this row did not apply", so the operator
 *       got a world that had moved and an applied count that said it had not.</li>
 *   <li><strong>Entity removal must tolerate drift.</strong> A dropped item
 *       falls and slides, so a rollback that requires the entity to still sit on
 *       the recorded block removes nothing and reports a row it cannot explain.</li>
 * </ol>
 *
 * <p>The drift policy is asserted directly through
 * {@link WorldMutator#selectNearestEntityCandidate} — it is pure arithmetic, so
 * the test states the intended behaviour rather than pinning source text. A
 * structural test that greps for {@code blockPosition().equals(pos)} would pass
 * on a rewrite that reintroduced the defect in another spelling.
 */
class WorldMutatorContractTest {

    // ------------------------------------------------------------ defaults are inert

    @Test
    void legacyVoidOnlyImplementation_mutates_nothing_on_the_checked_path() {
        // An implementation written against the pre-1.3.10 ABI: it overrides the
        // void methods and none of the checked ones. This is the shape that used
        // to silently mutate and report failure.
        LegacyVoidOnly legacy = new LegacyVoidOnly();

        assertThat(legacy.trySetBlock("minecraft:overworld", 0, 64, 0, "minecraft:stone", null))
                .as("checked setBlock on a legacy implementation is unsupported, not a silent mutation")
                .isFalse();
        assertThat(legacy.trySetBlock("minecraft:overworld", 0, 64, 0, "minecraft:chest", null,
                "facing=north", new byte[]{1, 2, 3}))
                .isFalse();
        assertThat(legacy.tryClearBlock("minecraft:overworld", 1, 64, 1)).isFalse();
        assertThat(legacy.tryGiveOrDrop("minecraft:overworld", 0, 64, 0, "minecraft:diamond", 1, null))
                .isFalse();
        assertThat(legacy.tryGiveOrDrop("minecraft:overworld", 0, 64, 0, "minecraft:diamond", 1, null,
                new byte[]{4})).isFalse();
        assertThat(legacy.tryRemoveFromContainer("minecraft:overworld", 0, 64, 0, "minecraft:diamond", 1))
                .isFalse();
        assertThat(legacy.tryRespawnEntity("minecraft:overworld", 0, 64, 0, "minecraft:zombie", null))
                .isFalse();
        assertThat(legacy.tryRespawnEntity("minecraft:overworld", 0, 64, 0, "minecraft:zombie", null,
                new byte[]{5})).isFalse();
        assertThat(legacy.tryRemoveEntity("minecraft:overworld", 0, 64, 0, "minecraft:item"))
                .as("legacy defaults must not claim to have removed an entity")
                .isFalse();
        assertThat(legacy.tryAddToPlayerInventory(UUID.randomUUID(), "minecraft:diamond", 1, null,
                new byte[]{6})).isFalse();
        assertThat(legacy.tryRemoveFromPlayerInventory(UUID.randomUUID(), "minecraft:diamond", 1, null,
                new byte[]{7})).isFalse();

        assertThat(legacy.mutations)
                .as("no checked default may reach the legacy void method: that is a mutation the "
                        + "engine will report as not applied")
                .isEmpty();
    }

    @Test
    void everyCheckedDefault_answersFalse() {
        WorldMutator defaults = new WorldMutator() { };

        List<String> claimed = new ArrayList<>();
        if (defaults.trySetBlock("w", 0, 0, 0, "minecraft:stone", null)) claimed.add("trySetBlock");
        if (defaults.trySetBlock("w", 0, 0, 0, "minecraft:chest", null, null, new byte[]{1})) {
            claimed.add("trySetBlock(nbt)");
        }
        if (defaults.tryClearBlock("w", 0, 0, 0)) claimed.add("tryClearBlock");
        if (defaults.tryGiveOrDrop("w", 0, 0, 0, "minecraft:diamond", 1, null)) claimed.add("tryGiveOrDrop");
        if (defaults.tryGiveOrDrop("w", 0, 0, 0, "minecraft:diamond", 1, null, new byte[]{2})) {
            claimed.add("tryGiveOrDrop(nbt)");
        }
        if (defaults.tryRemoveFromContainer("w", 0, 0, 0, "minecraft:diamond", 1)) {
            claimed.add("tryRemoveFromContainer");
        }
        if (defaults.tryRespawnEntity("w", 0, 0, 0, "minecraft:zombie", null)) claimed.add("tryRespawnEntity");
        if (defaults.tryRespawnEntity("w", 0, 0, 0, "minecraft:zombie", null, new byte[]{3})) {
            claimed.add("tryRespawnEntity(nbt)");
        }
        if (defaults.tryRemoveEntity("w", 0, 0, 0, "minecraft:item")) claimed.add("tryRemoveEntity");
        if (defaults.tryAddToPlayerInventory(UUID.randomUUID(), "minecraft:diamond", 1, null, null)) {
            claimed.add("tryAddToPlayerInventory");
        }
        if (defaults.tryRemoveFromPlayerInventory(UUID.randomUUID(), "minecraft:diamond", 1, null, null)) {
            claimed.add("tryRemoveFromPlayerInventory");
        }
        if (defaults.tryAddToContainer("w", 0, 0, 0, "minecraft:diamond", 1, null, null, 4)) {
            claimed.add("tryAddToContainer(slot)");
        }
        if (defaults.tryRemoveFromContainer("w", 0, 0, 0, "minecraft:diamond", 1, null, null, 4)) {
            claimed.add("tryRemoveFromContainer(slot)");
        }

        assertThat(claimed)
                .as("a checked method without an override cannot provide success evidence")
                .isEmpty();
    }

    @Test
    void nbtCheckedDefaults_doNotFallThroughToTheNbtlessOverload() {
        // A fall-through here is not a no-op: it calls a *real* override with the
        // NBT payload dropped, so a container restores empty or an enchanted item
        // restores plain — and the call still answers true.
        NbtlessOnlyMutator mutator = new NbtlessOnlyMutator();

        assertThat(mutator.trySetBlock("w", 0, 64, 0, "minecraft:chest", null,
                "facing=north", new byte[]{1})).isFalse();
        assertThat(mutator.tryGiveOrDrop("w", 0, 64, 0, "minecraft:diamond_sword", 1, null,
                new byte[]{2})).isFalse();
        assertThat(mutator.tryRespawnEntity("w", 0, 64, 0, "minecraft:zombie", null,
                new byte[]{3})).isFalse();

        assertThat(mutator.calls).isEmpty();
    }

    @Test
    void slotlessContainerInsertion_keepsTheNbtPayload() {
        // inventorySlot == null is a historical row, not a request to strip NBT:
        // the fallback must carry itemNbt through to giveOrDrop.
        NbtRecordingMutator mutator = new NbtRecordingMutator();

        assertThat(mutator.tryAddToContainer("w", 8, 64, 8, "minecraft:diamond_sword", 1,
                null, new byte[]{9, 9}, null)).isTrue();

        assertThat(mutator.calls).containsExactly("giveOrDrop|nbtBytes=2");
    }

    // ------------------------------------------------------- drifted entity matching

    @Test
    void exactBlockMatchWinsOverACloserDriftedNeighbour() {
        // An entity one block away can have a smaller centre distance to the
        // recorded block's centre than an entity standing inside it at a corner.
        // Precision must beat distance when an exact match exists.
        List<WorldMutator.EntityCandidate> candidates = List.of(
                new WorldMutator.EntityCandidate(11, 64, 10, 0.30),   // drifted, near the centre
                new WorldMutator.EntityCandidate(10, 64, 10, 1.20));  // exact block, corner position

        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, candidates, 3))
                .as("the entity actually standing on the recorded block is the correct one")
                .isEqualTo(1);
    }

    @Test
    void driftedItemFallsAndIsStillMatched() {
        // The reported defect: an item dropped at y=64 falls to y=61 and is never
        // removed, so the rollback reports a row it cannot explain.
        List<WorldMutator.EntityCandidate> candidates = List.of(
                new WorldMutator.EntityCandidate(10, 61, 10, 9.0));

        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, candidates, 3))
                .as("a fall of three blocks must still match")
                .isEqualTo(0);
    }

    @Test
    void driftBeyondTheBoundIsNotMatched() {
        // The radius is bounded because every same-type entity inside it is an
        // unverifiable candidate. Beyond it, refusing is the honest answer.
        List<WorldMutator.EntityCandidate> candidates = List.of(
                new WorldMutator.EntityCandidate(10, 60, 10, 16.0),   // four blocks down
                new WorldMutator.EntityCandidate(14, 64, 10, 16.0));  // four blocks across

        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, candidates, 3))
                .as("outside the drift bound there is no defensible match")
                .isEqualTo(-1);
    }

    @Test
    void nearestDriftedCandidateWinsSoARerunIsDeterministic() {
        List<WorldMutator.EntityCandidate> candidates = List.of(
                new WorldMutator.EntityCandidate(10, 62, 10, 4.0),
                new WorldMutator.EntityCandidate(11, 63, 10, 1.5),
                new WorldMutator.EntityCandidate(9, 63, 10, 1.25));

        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, candidates, 3))
                .isEqualTo(2);

        // Order-independence: two runs over the same world state must choose the
        // same entity or the second run removes a different item.
        List<WorldMutator.EntityCandidate> reversed = new ArrayList<>(candidates);
        Collections.reverse(reversed);
        int fromReversed = WorldMutator.selectNearestEntityCandidate(10, 64, 10, reversed, 3);
        assertThat(reversed.get(fromReversed)).isEqualTo(candidates.get(2));
    }

    @Test
    void noCandidatesMeansNoRemoval() {
        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, List.of(), 3)).isEqualTo(-1);
        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, null, 3)).isEqualTo(-1);
    }

    @Test
    void unusableDistanceNeverSelectsACandidate() {
        List<WorldMutator.EntityCandidate> candidates = List.of(
                new WorldMutator.EntityCandidate(10, 63, 10, Double.NaN),
                new WorldMutator.EntityCandidate(10, 62, 10, 4.0));

        assertThat(WorldMutator.selectNearestEntityCandidate(10, 64, 10, candidates, 3))
                .as("a NaN distance must be skipped, not treated as nearer than everything")
                .isEqualTo(1);
    }

    @Test
    void driftBoundIsBoundedAndPositive() {
        assertThat(WorldMutator.REMOVE_ENTITY_MAX_DRIFT_BLOCKS)
                .as("an unbounded or zero radius defeats the purpose: zero loses drifted items, "
                        + "a large radius removes other players' entities")
                .isBetween(1, 8);
    }

    // ------------------------------------------------------------------- fixtures

    /** Implemented against the legacy ABI only, recording every mutation it makes. */
    private static final class LegacyVoidOnly implements WorldMutator {
        final List<String> mutations = new ArrayList<>();

        @Override public void setBlock(String w, int x, int y, int z, String t, String m) {
            mutations.add("setBlock");
        }
        @Override public void setBlock(String w, int x, int y, int z, String t, String m, String bs, byte[] nbt) {
            mutations.add("setBlock(nbt)");
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m) {
            mutations.add("giveOrDrop");
        }
        @Override public void giveOrDrop(String w, int x, int y, int z, String t, int a, String m, byte[] nbt) {
            mutations.add("giveOrDrop(nbt)");
        }
        @Override public void removeFromContainer(String w, int x, int y, int z, String t, int a) {
            mutations.add("removeFromContainer");
        }
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m) {
            mutations.add("respawnEntity");
        }
        @Override public void respawnEntity(String w, int x, int y, int z, String t, String m, byte[] nbt) {
            mutations.add("respawnEntity(nbt)");
        }
        @Override public void removeEntity(String w, int x, int y, int z, String t) {
            mutations.add("removeEntity");
        }
    }

    /** Overrides the NBT-less checked methods only, so a fall-through would be visible. */
    private static final class NbtlessOnlyMutator implements WorldMutator {
        final List<String> calls = new ArrayList<>();

        @Override public boolean trySetBlock(String w, int x, int y, int z, String t, String m) {
            calls.add("setBlock");
            return true;
        }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m) {
            calls.add("giveOrDrop");
            return true;
        }
        @Override public boolean tryRespawnEntity(String w, int x, int y, int z, String t, String m) {
            calls.add("respawnEntity");
            return true;
        }
    }

    /** Records the NBT payload it receives, to prove the slot-less fallback keeps it. */
    private static final class NbtRecordingMutator implements WorldMutator {
        final List<String> calls = new ArrayList<>();

        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m) {
            calls.add("giveOrDrop");
            return true;
        }
        @Override public boolean tryGiveOrDrop(String w, int x, int y, int z, String t, int a, String m, byte[] nbt) {
            calls.add("giveOrDrop|nbtBytes=" + (nbt == null ? 0 : nbt.length));
            return true;
        }
    }

    // ------------------------------------------------- 1.18.2 forge cell coverage

    private static final Path FORGE_1182 =
            Path.of("mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeWorldMutator.java");

    @Test
    void forge1182OverridesEveryCheckedMethodTheEngineCalls() throws Exception {
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        String src = stripComments(Files.readString(root.resolve(FORGE_1182)));

        // The engine's dispatch surface (applyInverse) must reach a real
        // implementation in this cell; anything left on a default answers false
        // and the row is reported as not applied. Declarations are matched by
        // name and parameter count, not by exact whitespace, so reformatting the
        // cell does not break the test.
        assertThat(overrides(src))
                .as("every checked method the engine dispatches must be overridden in 1.18.2 forge")
                .contains(
                        "trySetBlock/6",          // block set, no NBT
                        "trySetBlock/8",          // block set with state + block-entity NBT
                        "tryClearBlock/4",        // block clear
                        "tryRemoveFromContainer/6",   // container remove, scan-all
                        "tryRemoveFromContainer/9",   // container remove, slot-aware
                        "tryAddToContainer/9",        // container add, slot-aware
                        "tryRemoveEntity/5",      // entity remove
                        "tryRespawnEntity/6",     // entity respawn
                        "tryRespawnEntity/7",     // entity respawn with entity NBT
                        "tryGiveOrDrop/7",        // give-or-drop
                        "tryGiveOrDrop/8",        // give-or-drop with item NBT
                        "tryAddToPlayerInventory/6",     // player inventory add
                        "tryRemoveFromPlayerInventory/6"); // player inventory remove
    }

    @Test
    void forge1182EntityRemovalDelegatesToTheSharedDriftPolicy() throws Exception {
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        Path path = root.resolve(FORGE_1182);
        String src = stripComments(Files.readString(path));

        String remove = method(src, "public boolean tryRemoveEntity(String worldId, int x, int y, int z, String entityType)");
        assertThat(remove).as("ForgeWorldMutator (1.18.2) must implement tryRemoveEntity").isNotNull();
        assertThat(remove)
                .as("removal must use the contract's drift selection, not a second copy of the policy")
                .contains("WorldMutator.selectNearestEntityCandidate")
                .contains("WorldMutator.REMOVE_ENTITY_MAX_DRIFT_BLOCKS")
                .as("the entity type is the identity boundary and must match strictly")
                .contains("e.getType() == type")
                .as("an exact-position requirement is the defect this closes")
                .doesNotContain("blockPosition().equals(")
                .as("removal must be a real discard")
                .contains("discard()");
    }

    @Test
    void forge1182HasNoExactPositionEntityMatchLeft() throws Exception {
        // The other cells keep their own copies of the old exact-position match
        // until their lane lands. This pins the 1.18.2 priority cell on its own,
        // so a regression there cannot hide behind the cells still to be fixed.
        Path root = repoRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "repo root not resolvable");
        String src = stripComments(Files.readString(root.resolve(FORGE_1182)));
        assertThat(countOccurrences(src, "blockPosition().equals("))
                .as("1.18.2 forge must have no exact-position entity match left")
                .isZero();
    }

    /** Declarations of {@code public boolean try*}, as {@code name/paramCount}. */
    private static List<String> overrides(String src) {
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("public\\s+boolean\\s+(try\\w+)\\s*\\(([^)]*)\\)")
                .matcher(src);
        while (m.find()) {
            String params = m.group(2).trim();
            int arity = params.isEmpty() ? 0 : params.split(",").length;
            found.add(m.group(1) + "/" + arity);
        }
        return found;
    }

    /** Removes block and line comments so a mention in prose is not read as code. */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i += 2;
            } else if (c == '"' || c == '\'') {
                // Copy literals verbatim: a quote char literal such as '"' must not
                // be mistaken for the start of a string.
                char quote = c;
                out.append(c);
                i++;
                while (i < src.length() && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') {
                        out.append(src.charAt(i));
                        i++;
                        if (i >= src.length()) break;
                    }
                    out.append(src.charAt(i));
                    i++;
                }
                if (i < src.length()) {
                    out.append(src.charAt(i));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** Extracts a method body by brace matching, as the source-contract tests do. */
    private static String method(String src, String signature) {
        int sig = src.indexOf(signature);
        if (sig < 0) return null;
        int brace = src.indexOf('{', sig);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(sig, i + 1);
            }
        }
        return null;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
            if (dir == null) {
                return null;
            }
        }
        return null;
    }
}
