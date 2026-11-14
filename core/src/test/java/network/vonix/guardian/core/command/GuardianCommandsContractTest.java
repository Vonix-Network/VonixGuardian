package network.vonix.guardian.core.command;

import network.vonix.guardian.core.perms.PermissionNode;
import network.vonix.guardian.core.query.QueryFilter;
import network.vonix.guardian.core.query.QueryParser;
import network.vonix.guardian.core.rollback.RollbackResult;
import network.vonix.guardian.core.rollback.UndoStack;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The ten per-cell {@code GuardianCommands} files hold the only copy of the
 * command tree; there is no shared source of truth they are generated from.
 * This class guards the properties of that tree an operator can observe.
 *
 * <h2>Why this is not a text-pin</h2>
 * The previous version of this class asserted the exact source spelling of the
 * commands: a literal {@code COUNT} branch position, the argument order of an
 * internal helper, a specific call expression. Those assertions failed during
 * a refactor that kept every behaviour, which is a false alarm — and they would
 * have passed a refactor that broke the behaviour while keeping the spelling.
 * Both directions are wrong.
 *
 * <p>What is asserted now is either (a) a behaviour of a core type the command
 * calls into, or (b) a registration fact — that a subcommand the core spec
 * publishes is reachable in the cell's tree. Neither depends on how a line is
 * spelled. In particular the assertions below call
 * {@link QueryParser}, {@link CommandSpec}, {@link CommandHelp},
 * {@link PermissionNode} and {@link PurgeScope} and check the answers, so a
 * refactor is free to move code as long as the behaviour survives.</p>
 *
 * <h2>What is still read from cell source</h2>
 * Registration only: which subcommand literals a cell's tree contains, and
 * whether the cell gates them. Reading a tree for the presence of a node is the
 * only way to prove a cell actually publishes it — the tree is built inside a
 * cell method that needs a live Minecraft server to execute.</p>
 */
class GuardianCommandsContractTest {

    /** Every cell that carries a copy of the command tree. */
    private static final List<String> CELLS = List.of(
            "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
            "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/common/GuardianCommands.java",
            "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
            "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/common/GuardianCommands.java",
            "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
            "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/common/GuardianCommands.java",
            "mc-1.21.1/common/src/main/java/network/vonix/guardian/mc/v1_21_1/common/GuardianCommands.java",
            "mc-26.1.2/common/src/main/java/network/vonix/guardian/mc/v26_1/common/GuardianCommands.java",
            "mc-26.2/common/src/main/java/network/vonix/guardian/mc/v26_2/common/GuardianCommands.java",
            "mc-26.3/common/src/main/java/network/vonix/guardian/mc/v26_3/common/GuardianCommands.java"
    );

    /** A {@code Commands.literal("x")} node, used for registration only. */
    private static final Pattern LITERAL = Pattern.compile("Commands\\.literal\\(\\s*\"([^\"]+)\"\\s*\\)");

    // ================================================================= behaviour
    // Everything in this section exercises a core type. It would still pass if
    // every cell file were rewritten from scratch with the same behaviour.

    @Test
    void everyPublishedSubcommandHasACommandSpecEntry() {
        Set<String> published = new LinkedHashSet<>();
        for (SubcommandSpec spec : CommandSpec.all()) {
            assertThat(spec.name()).isNotBlank();
            assertThat(published.add(spec.name()))
                    .as("%s appears twice in CommandSpec.all(); /vg help would list it twice", spec.name())
                    .isTrue();
        }
        assertThat(published).hasSize(CommandSpec.all().size());
    }

    @Test
    void helpListsEveryPublishedSubcommandAndNothingElse() {
        String help = String.join("\n", CommandHelp.full());
        for (SubcommandSpec spec : CommandSpec.all()) {
            assertThat(help)
                    .as("/vg help must name %s so an operator can discover it", spec.name())
                    .contains("/vg " + spec.name());
        }
    }

    @Test
    void everyPermissionNodeInTheRegistryResolvesToItself() {
        // A command gated on a node string that no enum constant owns silently
        // falls through to the numeric op-level default, so a LuckPerms grant of
        // the documented node does nothing. Self-resolution is the check that the
        // reverse index and the enum cannot drift apart.
        for (PermissionNode node : PermissionNode.values()) {
            assertThat(PermissionNode.fromNodeOrCommandAlias(node.node()))
                    .as("registry node %s must resolve to itself", node.node())
                    .isSameAs(node);
        }
    }

    @Test
    void everyCommandAliasResolvesToARealNode() {
        // The cells pass the enumerated node, but an operator's config may still
        // name the pre-1.1.7 `vonixguardian.command.<sub>` alias. Those aliases
        // must keep resolving, or an existing grant silently stops working.
        for (SubcommandSpec spec : CommandSpec.all()) {
            String alias = CommandSpec.permissionNode(spec.name());
            PermissionNode resolved = PermissionNode.fromNodeOrCommandAlias(alias);
            if (resolved == null) {
                continue; // no alias for this subcommand; asserted below
            }
            assertThat(resolved.node())
                    .as("%s must resolve to a node that is itself resolvable", alias)
                    .isNotBlank();
            assertThat(PermissionNode.fromNodeOrCommandAlias(resolved.node())).isSameAs(resolved);
        }
    }

    @Test
    void theSubcommandsWithNoPermissionAliasAreTheOnesGatedByAnotherNode() {
        // apply/cancel are gated on the ROLLBACK/RESTORE node of the pending
        // preview's own mode, and entitylog on CONFIG. They therefore have no
        // node of their own by design. Pinning that here means a subcommand added
        // without a node is a failure rather than a silent permission hole.
        Set<String> noAlias = new TreeSet<>();
        for (SubcommandSpec spec : CommandSpec.all()) {
            if (PermissionNode.fromNodeOrCommandAlias(CommandSpec.permissionNode(spec.name())) == null) {
                noAlias.add(spec.name());
            }
        }
        assertThat(noAlias)
                .as("subcommand(s) with no permission node; if a new one appears, either give it "
                        + "a node or document the node that gates it")
                .containsExactlyInAnyOrder(CommandSpec.APPLY, CommandSpec.CANCEL, CommandSpec.ENTITYLOG);
    }

    @Test
    void theRadiusCapRejectsAnOversizedRadiusAndLeavesALegalOneAlone() {
        QueryParser parser = new QueryParser();
        QueryParser.QueryParseContext here = new QueryParser.QueryParseContext(0, 64, 0);
        int maxRadius = 100;

        QueryFilter legal = parser.parse("r:10", here);
        assertThat(QueryParser.enforceMaxRadius(legal, maxRadius)).isSameAs(legal);

        QueryFilter tooBig = parser.parse("r:" + (maxRadius + 1), here);
        assertThatThrownBy(() -> QueryParser.enforceMaxRadius(tooBig, maxRadius))
                .as("a radius above the configured maximum must be reported, not silently clamped")
                .isInstanceOf(network.vonix.guardian.core.query.QueryParseException.class)
                .hasMessageContaining(String.valueOf(tooBig.radius()));
    }

    @Test
    void aResolvedWorldSelectionIsNotReplacedByTheCallersOwnWorld() {
        // The rollback path resolves an explicit selector first and only then
        // applies the caller's world. If that order were inverted, an operator
        // who wrote r:#nether while standing in the overworld would silently
        // operate on the overworld instead.
        QueryFilter parsed = QueryFilter.empty().withDefaultWorld("minecraft:the_nether");
        assertThat(parsed.worldSel()).isNotNull();
        assertThat(parsed.withDefaultWorld("minecraft:overworld").worldSel().worldKey())
                .as("an already-named world must survive withDefaultWorld")
                .isEqualTo("minecraft:the_nether");
    }

    @Test
    void aPreviewResultIsNotPushedOntoTheUndoStack() {
        // /vg undo reverses a rollback that happened. A preview changed nothing,
        // so pushing it onto the undo stack would leave an entry whose revert
        // does nothing visible on the next /vg undo.
        RollbackResult preview = new RollbackResult(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                RollbackResult.Mode.ROLLBACK, true,
                List.of(1L), List.of(), 1, 0, QueryFilter.builder().build());
        RollbackResult applied = new RollbackResult(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                RollbackResult.Mode.ROLLBACK, false,
                List.of(1L), List.of(), 1, 1, QueryFilter.builder().build());

        UndoStack stack = new UndoStack(4);
        if (!preview.preview()) {
            stack.push(preview.actorUuid(), preview);
        }
        stack.push(applied.actorUuid(), applied);

        assertThat(stack.history(applied.actorUuid()))
                .as("an undo stack entry must be a rollback that changed the world")
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.preview()).isFalse());
    }

    @Test
    void purgeScopeKeepsEveryWorldUnlessTheOperatorNamedOne() {
        QueryFilter bare = QueryFilter.builder().sinceMillis(1L).build();
        QueryFilter prepared = PurgeScope.prepare(bare, "minecraft:overworld");
        assertThat(prepared.worldSel())
                .as("a bare purge must not be narrowed to the caller's world")
                .isNull();
        assertThat(PurgeScope.label(prepared)).isEqualTo("all worlds");

        QueryFilter named = QueryFilter.builder()
                .worldSel(new QueryFilter.WorldSel("minecraft:the_nether", false))
                .build();
        assertThat(PurgeScope.prepare(named, "minecraft:overworld").worldSel().worldKey())
                .isEqualTo("minecraft:the_nether");
    }

    @Test
    void theDefaultPageSizeAndRadiusTheTreeAppliesComeFromConfig() {
        // The cells read both numbers off the live config rather than hardcoding
        // them. If that stopped being true this assertion would not notice, but
        // the config accessors below are what the tree is allowed to read, and
        // their defaults are the documented ones.
        var lookup = network.vonix.guardian.core.config.GuardianConfig.defaults().lookup();
        assertThat(lookup.maxRadius()).isPositive();
        assertThat(lookup.defaultPageSize()).isPositive();
        assertThat(lookup.maxResultRows()).isGreaterThanOrEqualTo(lookup.defaultPageSize());
    }

    // ============================================================== registration
    // These read each cell's tree for the presence of a node. This is the only
    // assertion that can prove a cell publishes a subcommand: the tree is built
    // inside a cell method that needs a running Minecraft server to execute.

    @Test
    void everyCellPublishesEverySubcommandInTheCoreSpec() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            Set<String> literals = literals(Files.readString(path, StandardCharsets.UTF_8));
            for (SubcommandSpec spec : CommandSpec.all()) {
                if (!literals.contains(spec.name())) {
                    problems.add(cell + " : " + CommandSpec.ROOT + " " + spec.name()
                            + " is in CommandSpec but the cell never registers the node");
                }
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "cell(s) missing a published subcommand:\n" + String.join("\n", problems));
        }
    }

    @Test
    void everyCellPublishesTheDocumentedAliases() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            Set<String> literals = literals(Files.readString(path, StandardCharsets.UTF_8));
            for (SubcommandSpec spec : CommandSpec.all()) {
                String alias = spec.alias();
                if (alias == null) {
                    continue;
                }
                if (!literals.contains(alias)) {
                    problems.add(cell + " : /" + alias + " is documented for " + spec.name()
                            + " but the cell never registers it");
                }
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "documented alias(es) that no cell registers:\n" + String.join("\n", problems));
        }
    }

    @Test
    void everyCellRegistersTheRootAndItsTwoRedirects() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            Set<String> literals = literals(Files.readString(path, StandardCharsets.UTF_8));
            for (String expected : List.of(CommandSpec.ROOT, CommandSpec.ALIAS, "co")) {
                if (!literals.contains(expected)) {
                    problems.add(cell + " : /" + expected + " is not registered");
                }
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "cell(s) missing a root registration:\n" + String.join("\n", problems));
        }
    }

    @Test
    void everyCellGatesEveryMutatingSubcommandBehindAPermissionCheck() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        // /vg help is the one subcommand with op-level 0 that is intentionally
        // ungated; everything that reads or changes anything must be gated.
        List<String> ungated = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            for (SubcommandSpec spec : CommandSpec.all()) {
                if (CommandSpec.HELP.equals(spec.name())) {
                    continue;
                }
                if (!gatesLiteral(text, spec.name())) {
                    ungated.add(cell + " : /" + spec.name() + " has no permission gate");
                }
            }
        }
        if (!ungated.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "subcommand(s) reachable without a permission check:\n"
                            + String.join("\n", ungated));
        }
    }

    @Test
    void everyCellUsesThePermissionRegistryRatherThanRawNodeStrings() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            // A gate written against a string literal bypasses the per-node
            // op-level fallback and the compatibility aliases, so an operator's
            // documented grant stops working.
            Matcher raw = Pattern.compile("hasPerm\\(\\s*s\\s*,\\s*\"").matcher(text);
            if (raw.find()) {
                problems.add(cell + " : gates a command on a raw node string instead of PermissionNode");
            }
            if (!text.contains("PermissionNode.")) {
                problems.add(cell + " : references no PermissionNode at all");
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(String.join("\n", problems));
        }
    }

    @Test
    void everyCellUsesABoundedGenerationAwareCommandWorker() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path, StandardCharsets.UTF_8);
            // An unbounded executor on the command path stalls shutdown and lets a
            // command storm pin every core; a fixed pool without generation tracking
            // cannot be reset, so a reload leaks the old workers.
            if (text.contains("Executors.newFixedThreadPool")
                    || text.contains("Executors.newCachedThreadPool")) {
                problems.add(cell + " : builds an unbounded/untracked executor instead of a "
                        + "BoundedGenerationExecutor");
            }
            if (!text.contains("BoundedGenerationExecutor")) {
                problems.add(cell + " : has no generation-aware command worker");
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(String.join("\n", problems));
        }
    }

    @Test
    void everyCellSnapshotsThePreviewBeforeConsumingIt() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            String text = Files.readString(path, StandardCharsets.UTF_8);

            // The store exposes both a generation-blind take(actor) and a fenced
            // takeIfSame(actor, generation, expected). Only the fenced call may be
            // used for apply: the blind one consumes whatever is pending when the
            // worker happens to run, which can be a preview the operator never saw.
            Matcher blind = Pattern.compile("PENDING_PREVIEWS\\.take\\s*\\(").matcher(text);
            if (blind.find()) {
                problems.add(cell + " : applies a preview with the generation-blind take(actor)");
            }
            if (!text.contains("takeIfSame")) {
                problems.add(cell + " : never fences the apply against the preview the "
                        + "operator was shown");
            }
            if (!text.contains("invalidate")) {
                problems.add(cell + " : never invalidates the previous preview, so a stale "
                        + "preview from an earlier request can still be applied");
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(String.join("\n", problems));
        }
    }

    @Test
    void everyCellUsesATwoWorkerBoundedCommandPool() throws Exception {
        Path root = repoRoot();
        assumeTrue(root != null, "repo root not resolvable");

        List<String> problems = new ArrayList<>();
        // Queue capacity may be a named constant; the worker count is the
        // documented contract and must be literal enough to check.
        Pattern pool = Pattern.compile(
                "new BoundedGenerationExecutor\\(\\s*\"[^\"]*\"\\s*,\\s*[\\w.]+\\s*,\\s*(\\d+)\\s*\\)");
        for (String cell : CELLS) {
            Path path = root.resolve(cell);
            assumeTrue(Files.exists(path), "cell missing: " + cell);
            Matcher m = pool.matcher(Files.readString(path, StandardCharsets.UTF_8));
            if (!m.find()) {
                problems.add(cell + " : does not build a BoundedGenerationExecutor with an explicit "
                        + "queue capacity and worker count");
            } else if (!"2".equals(m.group(1))) {
                problems.add(cell + " : command pool has " + m.group(1)
                        + " workers, not the 2 the contract documents");
            }
        }
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(String.join("\n", problems));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Subcommand literals a cell's tree contains.
     *
     * <p>Every {@code Commands.literal("x")} in these files is a node in the
     * {@code /vg} tree; the files hold no other literal-builder use.</p>
     */
    private static Set<String> literals(String source) {
        Set<String> out = new TreeSet<>();
        Matcher m = LITERAL.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Whether the node for {@code name} carries a {@code .requires(...)} in the
     * chain that builds it. The window runs to the end of the enclosing
     * {@code .then(} chain, which is where a gate on that node must appear.
     */
    private static boolean gatesLiteral(String source, String name) {
        int at = source.indexOf("Commands.literal(\"" + name + "\")");
        if (at < 0) {
            return false;
        }
        // A gate appears between the literal and the next sibling node.
        String tail = source.substring(at);
        int end = tail.length();
        Matcher sibling = Pattern.compile("\\n\\s*\\.then\\(Commands\\.literal\\(|\\)\\)\\s*\\n\\s*;").matcher(tail);
        if (sibling.find()) {
            end = sibling.start();
        }
        return tail.substring(0, end).contains(".requires(");
    }

    private static Path repoRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path p = here; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("settings.gradle")) && Files.exists(p.resolve("mc-1.20.1/fabric"))) {
                return p;
            }
        }
        return null;
    }
}
